package org.umlg.sqlg.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.*;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.LoopTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.RepeatStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.*;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IdentityStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.TreeSideEffectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ElementValueComparator;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.TraversalComparator;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.T;
import org.umlg.sqlg.predicate.Text;
import org.umlg.sqlg.sql.parse.ReplacedStep;
import org.umlg.sqlg.structure.SqlgGraph;

import java.time.Duration;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * Created by pieter on 2015/07/19.
 */
public abstract class BaseSqlgStrategy extends AbstractTraversalStrategy<TraversalStrategy.OptimizationStrategy> implements TraversalStrategy.OptimizationStrategy {

    protected SqlgGraph sqlgGraph;
    protected static final List<Class> CONSECUTIVE_STEPS_TO_REPLACE = Arrays.asList(VertexStep.class, EdgeVertexStep.class, GraphStep.class);
    public static final String PATH_LABEL_SUFFIX = "P~~~";
    public static final String EMIT_LABEL_SUFFIX = "E~~~";
    public static final String SQLG_PATH_FAKE_LABEL = "sqlgPathFakeLabel";
    private static final List<BiPredicate> SUPPORTED_BI_PREDICATE = Arrays.asList(
            Compare.eq, Compare.neq, Compare.gt, Compare.gte, Compare.lt, Compare.lte);

    public BaseSqlgStrategy(SqlgGraph sqlgGraph) {
        this.sqlgGraph = sqlgGraph;
    }

    protected boolean unoptimizableRepeat(List<Step> steps, int index) {
        List<Step> toCome = steps.subList(index, steps.size());
        boolean repeatExist = toCome.stream().anyMatch(s -> s.getClass().equals(RepeatStep.class));
        if (repeatExist) {

//            boolean hasEmit = toCome.stream().filter(s -> s.getClass().equals(RepeatStep.class)).allMatch(r -> ((RepeatStep) r).getEmitTraversal() != null);
            boolean hasUntil = toCome.stream().filter(s -> s.getClass().equals(RepeatStep.class)).allMatch(r -> ((RepeatStep) r).getUntilTraversal() != null);
            boolean hasUnoptimizableUntil = false;
            if (hasUntil) {
                hasUnoptimizableUntil = toCome.stream().filter(s -> s.getClass().equals(RepeatStep.class)).allMatch(r -> !(((RepeatStep) r).getUntilTraversal() instanceof LoopTraversal));
            }

//            boolean badRepeat = hasEmit || !hasUntil || hasUnoptimizableUntil;
            boolean badRepeat = !hasUntil || hasUnoptimizableUntil;
            //Check if the repeat step only contains optimizable steps
            if (!badRepeat) {
                List<Step> collectedRepeatInternalSteps = new ArrayList<>();
                List<Step> repeatSteps = toCome.stream().filter(s -> s.getClass().equals(RepeatStep.class)).collect(Collectors.toList());
                for (Step step : repeatSteps) {
                    RepeatStep repeatStep = (RepeatStep) step;
                    List<Traversal.Admin> repeatTraversals = repeatStep.<Traversal.Admin>getGlobalChildren();
                    Traversal.Admin admin = repeatTraversals.get(0);
                    List<Step> repeatInternalSteps = admin.getSteps();
                    collectedRepeatInternalSteps.addAll(repeatInternalSteps);
                }
                return !collectedRepeatInternalSteps.stream().filter(s -> !s.getClass().equals(RepeatStep.RepeatEndStep.class))
                        .allMatch((s) -> {
                            return CONSECUTIVE_STEPS_TO_REPLACE.contains(s.getClass());
                        });
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    protected boolean canNotBeOptimized(List<Step> steps, int index) {
        List<Step> toCome = steps.subList(index, steps.size());
        return toCome.stream().anyMatch(s ->
                s.getClass().equals(Order.class));
    }

    protected boolean precedesPathOrTreeStep(List<Step> steps, int index) {
        List<Step> toCome = steps.subList(index, steps.size());
        return toCome.stream().anyMatch(s ->
                (s.getClass().equals(PathStep.class) ||
                        s.getClass().equals(TreeStep.class) ||
                        s.getClass().equals(TreeSideEffectStep.class)));
    }

    protected void collectHasSteps(ListIterator<Step> iterator, Traversal.Admin<?, ?> traversal, ReplacedStep<?, ?> replacedStep, int pathCount) {
        //Collect the hasSteps
        while (iterator.hasNext()) {
            Step<?, ?> currentStep = iterator.next();
            if (currentStep instanceof HasContainerHolder && isNotZonedDateTimeOrPeriodOrDuration((HasContainerHolder) currentStep) &&
                    (isSingleBiPredicate(((HasContainerHolder) currentStep).getHasContainers()) ||
                            isBetween(((HasContainerHolder) currentStep).getHasContainers()) ||
                            isInside(((HasContainerHolder) currentStep).getHasContainers()) ||
                            isOutside(((HasContainerHolder) currentStep).getHasContainers()) ||
                            isWithinOut(((HasContainerHolder) currentStep).getHasContainers()) ||
                            isTextContains(((HasContainerHolder) currentStep).getHasContainers()))) {
                if (!currentStep.getLabels().isEmpty()) {
                    final IdentityStep identityStep = new IdentityStep<>(traversal);
                    currentStep.getLabels().forEach(l -> replacedStep.addLabel(pathCount + BaseSqlgStrategy.PATH_LABEL_SUFFIX + l));
                    TraversalHelper.insertAfterStep(identityStep, currentStep, traversal);
                }
                iterator.remove();
                traversal.removeStep(currentStep);
                replacedStep.getHasContainers().addAll(((HasContainerHolder) currentStep).getHasContainers());
            } else if (currentStep instanceof IdentityStep) {
                // do nothing
            } else {
                iterator.previous();
                break;
            }
        }
    }

    protected boolean isNotZonedDateTimeOrPeriodOrDuration(HasContainerHolder currentStep) {
        return currentStep.getHasContainers().stream().filter(
                h -> h.getPredicate().getValue() instanceof ZonedDateTime ||
                        h.getPredicate().getValue() instanceof Period ||
                        h.getPredicate().getValue() instanceof Duration ||
                        (h.getPredicate().getValue() instanceof List && (
                                ((List) h.getPredicate().getValue()).stream().anyMatch(v -> v instanceof ZonedDateTime) ||
                                        ((List) h.getPredicate().getValue()).stream().anyMatch(v -> v instanceof Period) ||
                                        ((List) h.getPredicate().getValue()).stream().anyMatch(v -> v instanceof Duration)))
        ).count() < 1;
    }

    static void collectOrderGlobalSteps(Step step, ListIterator<Step> iterator, Traversal.Admin<?, ?> traversal, ReplacedStep<?, ?> replacedStep) {
        //Collect the OrderGlobalSteps
        if (step instanceof OrderGlobalStep && isElementValueComparator((OrderGlobalStep) step)) {
            iterator.remove();
            traversal.removeStep(step);
            replacedStep.getComparators().addAll(((OrderGlobalStep) step).getComparators());
        } else {
            collectSelectOrderGlobalSteps(iterator, traversal, replacedStep);
        }
    }

    private static void collectSelectOrderGlobalSteps(ListIterator<Step> iterator, Traversal.Admin<?, ?> traversal, ReplacedStep<?, ?> replacedStep) {
        //Collect the OrderGlobalSteps
        while (iterator.hasNext()) {
            Step<?, ?> currentStep = iterator.next();
            if (currentStep instanceof OrderGlobalStep && isElementValueComparator((OrderGlobalStep) currentStep)) {
                iterator.remove();
                traversal.removeStep(currentStep);
                replacedStep.getComparators().addAll(((OrderGlobalStep) currentStep).getComparators());
            } else if (currentStep instanceof OrderGlobalStep && isTraversalComparatorWithSelectOneStep((OrderGlobalStep) currentStep)) {
                iterator.remove();
                traversal.removeStep(currentStep);
                replacedStep.getComparators().addAll(((OrderGlobalStep) currentStep).getComparators());
            } else if (currentStep instanceof IdentityStep) {
                // do nothing
            } else {
                iterator.previous();
                break;
            }
        }
    }

    static boolean isElementValueComparator(OrderGlobalStep orderGlobalStep) {
        return orderGlobalStep.getComparators().stream().allMatch(c -> c instanceof ElementValueComparator
                && (((ElementValueComparator) c).getValueComparator() == Order.incr ||
                ((ElementValueComparator) c).getValueComparator() == Order.decr));
    }

    static boolean isTraversalComparatorWithSelectOneStep(OrderGlobalStep orderGlobalStep) {
        for (Object o : orderGlobalStep.getComparators()) {
            if (o instanceof TraversalComparator) {
                TraversalComparator traversalComparator = (TraversalComparator) o;
                List<Step> traversalComparatorSteps = traversalComparator.getTraversal().getSteps();
                return traversalComparatorSteps.size() == 1 && traversalComparatorSteps.get(0) instanceof SelectOneStep;
            } else {
                return false;
            }
        }
        if (orderGlobalStep.getComparators().stream().allMatch(c -> c instanceof TraversalComparator)) {
        } else {
            return false;
        }
        return orderGlobalStep.getComparators().stream().allMatch(c -> c instanceof TraversalComparator
                && (((ElementValueComparator) c).getValueComparator() == Order.incr ||
                ((ElementValueComparator) c).getValueComparator() == Order.decr));
    }

//    protected void collectSelectOrderGlobalSteps(ListIterator<Step> iterator, Traversal.Admin<?, ?> traversal, ReplacedStep<?, ?> replacedStep) {
//        //Collect the OrderGlobalSteps
//        while (iterator.hasNext()) {
//            Step<?, ?> currentStep = iterator.next();
//            if (currentStep instanceof OrderGlobalStep
//                    && ((OrderGlobalStep) currentStep).getComparators().stream().allMatch(c -> c instanceof ElementValueComparator
//                    && (((ElementValueComparator) c).getValueComparator() == Order.incr ||
//                    ((ElementValueComparator) c).getValueComparator() == Order.decr))) {
//                iterator.remove();
//                traversal.removeStep(currentStep);
//                replacedStep.getComparators().addAll(((OrderGlobalStep) currentStep).getComparators());
//            } else if (currentStep instanceof IdentityStep) {
//                // do nothing
//            } else {
//                iterator.previous();
//                break;
//            }
//        }
//    }

    protected boolean hasOrderGlobalSteps(ListIterator<Step> iterator) {
        //Collect the OrderGlobalSteps
        while (iterator.hasNext()) {
            Step<?, ?> currentStep = iterator.next();
            if (currentStep instanceof OrderGlobalStep
                    && ((OrderGlobalStep) currentStep).getComparators().stream().allMatch(c -> c instanceof ElementValueComparator
                    && (((ElementValueComparator) c).getValueComparator() == Order.incr ||
                    ((ElementValueComparator) c).getValueComparator() == Order.decr))) {
                return true;
            } else if (currentStep instanceof IdentityStep) {
                // do nothing
            } else {
                iterator.previous();
                return false;
            }
        }
        return false;
    }

    private boolean isSingleBiPredicate(List<HasContainer> hasContainers) {
        if (hasContainers.size() == 1) {
            return SUPPORTED_BI_PREDICATE.contains(hasContainers.get(0).getBiPredicate());
        } else {
            return false;
        }
    }

    private boolean isBetween(List<HasContainer> hasContainers) {
        if (hasContainers.size() == 2) {
            HasContainer hasContainer1 = hasContainers.get(0);
            HasContainer hasContainer2 = hasContainers.get(1);
            return hasContainer1.getBiPredicate().equals(Compare.gte) && hasContainer2.getBiPredicate().equals(Compare.lt);
        } else {
            return false;
        }
    }

    private boolean isInside(List<HasContainer> hasContainers) {
        if (hasContainers.size() == 2) {
            HasContainer hasContainer1 = hasContainers.get(0);
            HasContainer hasContainer2 = hasContainers.get(1);
            return hasContainer1.getBiPredicate().equals(Compare.gt) && hasContainer2.getBiPredicate().equals(Compare.lt);
        } else {
            return false;
        }
    }

    private <V> boolean isOutside(List<HasContainer> hasContainers) {
        if (hasContainers.size() == 1 && hasContainers.get(0).getPredicate() instanceof OrP) {
            OrP<V> orP = (OrP) hasContainers.get(0).getPredicate();
            if (orP.getPredicates().size() == 2) {
                P<V> predicate1 = orP.getPredicates().get(0);
                P<V> predicate2 = orP.getPredicates().get(1);
                return predicate1.getBiPredicate().equals(Compare.lt) && predicate2.getBiPredicate().equals(Compare.gt);
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    private boolean isWithinOut(List<HasContainer> hasContainers) {
        return (hasContainers.size() == 1 && !hasContainers.get(0).getKey().equals(T.label.getAccessor()) &&
                !hasContainers.get(0).getKey().equals(T.id.getAccessor()) &&
                (hasContainers.get(0).getBiPredicate() == Contains.without || hasContainers.get(0).getBiPredicate() == Contains.within));
    }

    private boolean isTextContains(List<HasContainer> hasContainers) {
        return (hasContainers.size() == 1 && !hasContainers.get(0).getKey().equals(T.label.getAccessor()) &&
                !hasContainers.get(0).getKey().equals(T.id.getAccessor()) &&
                (hasContainers.get(0).getBiPredicate() == Text.contains ||
                        hasContainers.get(0).getBiPredicate() == Text.ncontains ||
                        hasContainers.get(0).getBiPredicate() == Text.containsCIS ||
                        hasContainers.get(0).getBiPredicate() == Text.ncontainsCIS ||
                        hasContainers.get(0).getBiPredicate() == Text.startsWith ||
                        hasContainers.get(0).getBiPredicate() == Text.nstartsWith ||
                        hasContainers.get(0).getBiPredicate() == Text.endsWith ||
                        hasContainers.get(0).getBiPredicate() == Text.nendsWith
                ));
    }

}
