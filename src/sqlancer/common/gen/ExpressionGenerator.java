package sqlancer.common.gen;

public interface ExpressionGenerator<E> {

    E generatePredicate();

    E negatePredicate(E predicate);

    E isNull(E expr);

}
