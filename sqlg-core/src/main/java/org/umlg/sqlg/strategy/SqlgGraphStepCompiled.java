package org.umlg.sqlg.strategy;

import com.google.common.base.Preconditions;
import com.google.common.collect.Multimap;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.GraphStep;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.util.iterator.EmptyIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.umlg.sqlg.sql.parse.ReplacedStep;
import org.umlg.sqlg.sql.parse.SchemaTableTree;
import org.umlg.sqlg.structure.*;
import org.umlg.sqlg.util.SqlgUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

/**
 * Date: 2015/02/20
 * Time: 9:54 PM
 */
public class SqlgGraphStepCompiled<S extends SqlgElement, E extends SqlgElement> extends GraphStep<S> {

    protected Supplier<Iterator<Pair<E, Multimap<String, Object>>>> iteratorSupplier;
    private List<ReplacedStep<S, E>> replacedSteps = new ArrayList<>();
    private SqlgGraph sqlgGraph;
    private Logger logger = LoggerFactory.getLogger(SqlgGraphStepCompiled.class.getName());

    public SqlgGraphStepCompiled(final SqlgGraph sqlgGraph, final Traversal.Admin traversal, final Class<S> returnClass, final Object... ids) {
        super(traversal, returnClass, ids);
        this.sqlgGraph = sqlgGraph;
        if ((this.ids.length == 0 || !(this.ids[0] instanceof Element))) {
            if (Vertex.class.isAssignableFrom(this.returnClass)) {
                this.iteratorSupplier = this::vertices;
            } else {
                this.iteratorSupplier = this::edges;
            }
//            this.setIteratorSupplier(() -> (Iterator<S>) (Vertex.class.isAssignableFrom(this.returnClass) ? this.vertices() : this.edges()));
        }
    }

    @Override
    protected Traverser<S> processNextStart() {
        if (this.first) {
            this.start = null == this.iteratorSupplier ? EmptyIterator.instance() : this.iteratorSupplier.get();
            if (null != this.start) {
                if (this.start instanceof Iterator)
                    this.starts.add(this.getTraversal().getTraverserGenerator().generateIterator((Iterator<S>) this.start, this, 1l));
                else
                    this.starts.add(this.getTraversal().getTraverserGenerator().generate((S) this.start, this, 1l));
            }
            this.first = false;
        }
        return this.starts.next();
    }

    private Iterator<Pair<E, Multimap<String, Object>>> edges() {
        return null;
    }

    private Iterator<Pair<E, Multimap<String, Object>>> vertices() {
        Preconditions.checkState(this.replacedSteps.size() > 0, "There must be at least one replacedStep");
        Preconditions.checkState(this.replacedSteps.get(0).isGraphStep(), "The first step must a SqlgGraphStep");
        Set<SchemaTableTree> rootSchemaTableTree = this.sqlgGraph.getGremlinParser().parse(this.replacedSteps);
        SqlgCompiledResultIterator<Pair<E, Multimap<String, Object>>> resultIterator = new SqlgCompiledResultIterator<>();
        for (SchemaTableTree schemaTableTree : rootSchemaTableTree) {
            List<Pair<LinkedList<SchemaTableTree>, String>> sqlStatements = schemaTableTree.constructSql();
            for (Pair<LinkedList<SchemaTableTree>, String> sqlPair : sqlStatements) {
                Connection conn = this.sqlgGraph.tx().getConnection();
                if (logger.isDebugEnabled()) {
                    logger.debug(sqlPair.getRight());
                }
                try (PreparedStatement preparedStatement = conn.prepareStatement(sqlPair.getRight())) {
                    SqlgUtil.setParametersOnStatement(this.sqlgGraph, sqlPair.getLeft(), conn, preparedStatement);
                    ResultSet resultSet = preparedStatement.executeQuery();
                    while (resultSet.next()) {
                        Pair<E, Multimap<String, Object>> result = SqlgUtil.loadElementsLabeledAndEndElements(this.sqlgGraph, resultSet, sqlPair.getLeft());
                        resultIterator.add(result);
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return resultIterator;
    }

    void addReplacedStep(ReplacedStep<S, E> replacedStep) {
        //depth is + 1 because there is always a root node who's depth is 0
        replacedStep.setDepth(this.replacedSteps.size() + 1);
        this.replacedSteps.add(replacedStep);
    }

}
