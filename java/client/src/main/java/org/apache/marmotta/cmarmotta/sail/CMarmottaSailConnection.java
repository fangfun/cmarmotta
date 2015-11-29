package org.apache.marmotta.cmarmotta.sail;

import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.Empty;
import com.google.protobuf.Int64Value;
import info.aduna.iteration.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.marmotta.cmarmotta.client.proto.Sail;
import org.apache.marmotta.cmarmotta.client.proto.SailServiceGrpc;
import org.apache.marmotta.cmarmotta.model.ProtoBNode;
import org.apache.marmotta.cmarmotta.model.ProtoNamespace;
import org.apache.marmotta.cmarmotta.model.ProtoStatement;
import org.apache.marmotta.cmarmotta.model.ProtoURI;
import org.apache.marmotta.cmarmotta.model.proto.Model;
import org.openrdf.model.*;
import org.openrdf.query.BindingSet;
import org.openrdf.query.Dataset;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.QueryInterruptedException;
import org.openrdf.query.algebra.QueryRoot;
import org.openrdf.query.algebra.StatementPattern;
import org.openrdf.query.algebra.TupleExpr;
import org.openrdf.query.algebra.Var;
import org.openrdf.query.algebra.evaluation.EvaluationStrategy;
import org.openrdf.query.algebra.evaluation.TripleSource;
import org.openrdf.query.algebra.evaluation.impl.*;
import org.openrdf.query.impl.EmptyBindingSet;
import org.openrdf.sail.SailException;
import org.openrdf.sail.UnknownSailTransactionStateException;
import org.openrdf.sail.helpers.NotifyingSailConnectionBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Add file description here!
 *
 * @author Sebastian Schaffert (sschaffert@apache.org)
 */
public class CMarmottaSailConnection extends NotifyingSailConnectionBase {

    private static Logger log = LoggerFactory.getLogger(CMarmottaSailConnection.class);

    private final ManagedChannel channel;
    private final SailServiceGrpc.SailServiceBlockingStub stub;
    private final SailServiceGrpc.SailServiceStub astub;

    private SettableFuture<Void> finishFuture;
    private StreamObserver<Sail.UpdateResponse> updateResponseObserver;
    private StreamObserver<Sail.UpdateRequest> updateRequestObserver;

    public CMarmottaSailConnection(CMarmottaSail parent, String host, int port) {
        super(parent);
        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext(true)
                .build();
        stub = SailServiceGrpc.newBlockingStub(channel);
        astub = SailServiceGrpc.newStub(channel);

        updateResponseObserver = new StreamObserver<Sail.UpdateResponse>() {
            @Override
            public void onNext(Sail.UpdateResponse updateResponse) {
                log.info(
                        "Committed transaction (added statements={}, removed statements={}, added namespaces={}, removed namespaces={})",
                        updateResponse.getAddedStatements(), updateResponse.getRemovedStatements(),
                        updateResponse.getAddedNamespaces(), updateResponse.getRemovedNamespaces());
            }

            @Override
            public void onError(Throwable throwable) {
                finishFuture.setException(throwable);
            }

            @Override
            public void onCompleted() {
                finishFuture.set(null);
            }
        };
    }

    @Override
    protected void addStatementInternal(Resource subj, URI pred, Value obj, Resource... contexts) throws SailException {
        log.info("Adding statements.");

        if (contexts.length > 0) {
            for (Resource ctx : contexts) {
                ProtoStatement stmt = new ProtoStatement(subj, pred, obj, ctx);
                Sail.UpdateRequest u = Sail.UpdateRequest.newBuilder().setStmtAdded(stmt.getMessage()).build();
                updateRequestObserver.onNext(u);
            }
        } else {
            ProtoStatement stmt = new ProtoStatement(subj, pred, obj, null);
            Sail.UpdateRequest u = Sail.UpdateRequest.newBuilder().setStmtAdded(stmt.getMessage()).build();
            updateRequestObserver.onNext(u);
        }
    }

    @Override
    protected void closeInternal() throws SailException {
        log.info("Closing connection.");
        commit();

        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            new SailException("Shutdown interrupted", e);
        }
    }

    @Override
    protected CloseableIteration<? extends BindingSet, QueryEvaluationException> evaluateInternal(TupleExpr tupleExpr, Dataset dataset, BindingSet bindings, boolean includeInferred) throws SailException {
        // Clone the tuple expression to allow for more aggressive optimizations
        tupleExpr = tupleExpr.clone();

        if (!(tupleExpr instanceof QueryRoot)) {
            // Add a dummy root node to the tuple expressions to allow the
            // optimizers to modify the actual root node
            tupleExpr = new QueryRoot(tupleExpr);
        }

        try {
            CMarmottaTripleSource tripleSource = new CMarmottaTripleSource(this,includeInferred);
            EvaluationStrategy strategy = new EvaluationStrategyImpl(tripleSource, dataset);

            new BindingAssigner().optimize(tupleExpr, dataset, bindings);
            new ConstantOptimizer(strategy).optimize(tupleExpr, dataset, bindings);
            new CompareOptimizer().optimize(tupleExpr, dataset, bindings);
            new ConjunctiveConstraintSplitter().optimize(tupleExpr, dataset, bindings);
            new DisjunctiveConstraintOptimizer().optimize(tupleExpr, dataset, bindings);
            new SameTermFilterOptimizer().optimize(tupleExpr, dataset, bindings);
            new QueryModelNormalizer().optimize(tupleExpr, dataset, bindings);
            new QueryJoinOptimizer(new InternalEvaluationStatistics()).optimize(tupleExpr, dataset, bindings);
            new IterativeEvaluationOptimizer().optimize(tupleExpr, dataset, bindings);
            new FilterOptimizer().optimize(tupleExpr, dataset, bindings);
            new OrderLimitOptimizer().optimize(tupleExpr, dataset, bindings);

            return strategy.evaluate(tupleExpr, EmptyBindingSet.getInstance());

        } catch (QueryEvaluationException e) {
            throw new SailException(e);
        }
    }

    @Override
    protected CloseableIteration<? extends Resource, SailException> getContextIDsInternal() throws SailException {
        log.info("Committing transaction before querying ...");
        commitForQuery();

        return wrapResourceIterator(stub.getContexts(Empty.getDefaultInstance()));
    }

    @Override
    protected CloseableIteration<? extends Statement, SailException> getStatementsInternal(Resource subj, URI pred, Value obj, boolean includeInferred, Resource... contexts) throws SailException {
        log.info("Committing transaction before querying ...");
        commitForQuery();

        if (contexts.length > 0) {
            ArrayList<CloseableIteration<? extends Statement, SailException>> iterators = new ArrayList<>(contexts.length);
            for (Resource ctx : contexts) {
                final ProtoStatement pattern = new ProtoStatement(subj, pred, obj, ctx);
                iterators.add(new DelayedIteration<Statement, SailException>() {
                    @Override
                    protected Iteration<? extends Statement, ? extends SailException> createIteration() throws SailException {
                        return wrapStatementIterator(stub.getStatements(pattern.getMessage()));
                    }
                });
            }
            return new UnionIteration<>(iterators);
        }

        ProtoStatement pattern = new ProtoStatement(subj, pred, obj, null);
        return wrapStatementIterator(stub.getStatements(pattern.getMessage()));
    }

    @Override
    protected long sizeInternal(Resource... contexts) throws SailException {
        log.info("Committing transaction before querying ...");
        commitForQuery();

        Sail.ContextRequest.Builder builder = Sail.ContextRequest.newBuilder();
        for (Resource ctx : contexts) {
            if (ctx instanceof URI) {
                builder.addContextBuilder().getUriBuilder().setUri(ctx.stringValue());
            } else if(ctx instanceof BNode) {
                builder.addContextBuilder().getBnodeBuilder().setId(ctx.stringValue());
            }
        }

        Int64Value v = stub.size(builder.build());
        return v.getValue();
    }

    @Override
    protected void startTransactionInternal() throws SailException {
        finishFuture = SettableFuture.create();
        updateRequestObserver = astub.update(updateResponseObserver);
    }

    protected void commitForQuery() throws SailException {
        if (isActive()) {
            commitInternal();
            startTransactionInternal();
        }
    }

    @Override
    protected void commitInternal() throws SailException {
        if (updateRequestObserver != null) {
            log.info("Start transaction commit");
            updateRequestObserver.onCompleted();
            try {
                finishFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new SailException("Error while writing to server", e);
            }
            updateRequestObserver = null;
            log.info("Transaction committed.");
        }
    }

    @Override
    protected void rollbackInternal() throws SailException {
        updateRequestObserver.onError(new Exception("transaction rollback"));
        updateRequestObserver = null;
    }

    @Override
    protected void removeStatementsInternal(Resource subj, URI pred, Value obj, Resource... contexts) throws SailException {
        log.info("Removing statements.");
        commitForQuery();

        if (contexts.length > 0) {
            for (Resource ctx : contexts) {
                ProtoStatement stmt = new ProtoStatement(subj, pred, obj, ctx);
                Sail.UpdateRequest u = Sail.UpdateRequest.newBuilder().setStmtRemoved(stmt.getMessage()).build();
                updateRequestObserver.onNext(u);
            }
        } else {
            ProtoStatement stmt = new ProtoStatement(subj, pred, obj, null);
            Sail.UpdateRequest u = Sail.UpdateRequest.newBuilder().setStmtRemoved(stmt.getMessage()).build();
            updateRequestObserver.onNext(u);
        }
    }

    @Override
    protected void clearInternal(Resource... contexts) throws SailException {
        log.info("Clearing statements.");
        commitForQuery();

        if (contexts.length > 0) {
            for (Resource ctx : contexts) {
                ProtoStatement stmt = new ProtoStatement(null, null, null, ctx);
                Sail.UpdateRequest u = Sail.UpdateRequest.newBuilder().setStmtRemoved(stmt.getMessage()).build();
                updateRequestObserver.onNext(u);
            }
        } else {
            ProtoStatement stmt = new ProtoStatement(null, null, null, null);
            Sail.UpdateRequest u = Sail.UpdateRequest.newBuilder().setStmtRemoved(stmt.getMessage()).build();
            updateRequestObserver.onNext(u);
        }
    }

    @Override
    protected CloseableIteration<? extends Namespace, SailException> getNamespacesInternal() throws SailException {
        log.info("Getting namespaces.");
        commitForQuery();

        Empty pattern = Empty.getDefaultInstance();
        return wrapNamespaceIterator(stub.getNamespaces(pattern));
    }

    @Override
    protected String getNamespaceInternal(String prefix) throws SailException {
        log.info("Committing transaction before querying ...");
        commitForQuery();

        Model.Namespace pattern = Model.Namespace.newBuilder().setPrefix(prefix).build();
        try {
            return stub.getNamespace(pattern).getUri();
        } catch (io.grpc.StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return null;
            }
            throw new SailException(ex);
        }
    }

    @Override
    protected void setNamespaceInternal(String prefix, String name) throws SailException {
        log.info("Setting namespace {} = {}.", prefix, name);

        ProtoNamespace ns = new ProtoNamespace(prefix, name);
        Sail.UpdateRequest u = Sail.UpdateRequest.newBuilder().setNsAdded(ns.getMessage()).build();
        updateRequestObserver.onNext(u);

    }

    @Override
    protected void removeNamespaceInternal(String prefix) throws SailException {
        log.info("Removing namespace {}.", prefix);
        commitForQuery();

        Sail.UpdateRequest.Builder builder = Sail.UpdateRequest.newBuilder();
        builder.getNsRemovedBuilder().setPrefix(prefix);
        updateRequestObserver.onNext(builder.build());
    }

    @Override
    protected void clearNamespacesInternal() throws SailException {
        log.info("Clearing namespaces.");
        commitForQuery();

        Sail.UpdateRequest.Builder builder = Sail.UpdateRequest.newBuilder();
        builder.setNsRemoved(Model.Namespace.getDefaultInstance());
        updateRequestObserver.onNext(builder.build());
    }

    private static CloseableIteration<Statement, SailException> wrapStatementIterator(Iterator<Model.Statement> it) {
        return new ConvertingIteration<Model.Statement, Statement, SailException>(
                new IteratorIteration<Model.Statement, SailException>(it)) {
            @Override
            protected Statement convert(Model.Statement sourceObject) throws SailException {
                return new ProtoStatement(sourceObject);
            }
        };
    }

    private static CloseableIteration<Namespace, SailException> wrapNamespaceIterator(Iterator<Model.Namespace> it) {
        return new ConvertingIteration<Model.Namespace, Namespace, SailException>(
                new IteratorIteration<Model.Namespace, SailException>(it)) {
            @Override
            protected Namespace convert(Model.Namespace sourceObject) throws SailException {
                return new ProtoNamespace(sourceObject);
            }
        };
    }

    private static CloseableIteration<Resource, SailException> wrapResourceIterator(Iterator<Model.Resource> it) {
        return new ConvertingIteration<Model.Resource, Resource, SailException>(
                new IteratorIteration<Model.Resource, SailException>(it)) {
            @Override
            protected Resource convert(Model.Resource sourceObject) throws SailException {
                switch (sourceObject.getResourcesCase()) {
                    case URI:
                        return new ProtoURI(sourceObject.getUri());
                    case BNODE:
                        return new ProtoBNode(sourceObject.getBnode());
                }
                return null;
            }
        };
    }

    protected static class InternalEvaluationStatistics extends EvaluationStatistics {

        public InternalEvaluationStatistics() {
        }

        @Override
        protected CardinalityCalculator createCardinalityCalculator() {
            return new InternalCardinalityCalculator();
        }

        protected class InternalCardinalityCalculator extends CardinalityCalculator {

            @Override
            protected double getCardinality(StatementPattern sp) {
                return super.getCardinality(sp);
            }

            protected Value getConstantValue(Var var) {
                return (var != null) ? var.getValue() : null;
            }
        }
    }

    /**
     * A helper class using a CMarmottaSailConnection as triple source for SPARQL queries.
     */
    private static class CMarmottaTripleSource implements TripleSource {

        private boolean inferred;
        private CMarmottaSailConnection connection;

        private CMarmottaTripleSource(CMarmottaSailConnection connection, boolean inferred) {
            this.inferred   = inferred;
            this.connection = connection;
        }

        @Override
        public CloseableIteration<? extends Statement, QueryEvaluationException> getStatements(Resource subj, URI pred, Value obj, Resource... contexts) throws QueryEvaluationException {
            try {
                return new ExceptionConvertingIteration<Statement, QueryEvaluationException>(
                        connection.getStatements(subj, pred, obj, inferred, contexts)
                ) {
                    @Override
                    protected QueryEvaluationException convert(Exception e) {
                        if (e instanceof ClosedByInterruptException) {
                            return new QueryInterruptedException(e);
                        }
                        else if (e instanceof IOException) {
                            return new QueryEvaluationException(e);
                        }
                        else if (e instanceof SailException) {
                            return new QueryEvaluationException(e);
                        }
                        else if (e instanceof RuntimeException) {
                            throw (RuntimeException)e;
                        }
                        else if (e == null) {
                            throw new IllegalArgumentException("e must not be null");
                        }
                        else {
                            throw new IllegalArgumentException("Unexpected exception type: " + e.getClass(),e);
                        }
                    }
                };
            } catch (SailException ex) {
                throw new QueryEvaluationException(ex);
            }
        }

        @Override
        public ValueFactory getValueFactory() {
            return new CMarmottaValueFactory();
        }
    }

}