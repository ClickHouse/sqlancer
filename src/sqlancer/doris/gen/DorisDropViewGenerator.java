package sqlancer.doris.gen;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.doris.DorisProvider.DorisGlobalState;

public final class DorisDropViewGenerator {

    private DorisDropViewGenerator() {
    }

    public static SQLQueryAdapter dropView(DorisGlobalState globalState) {
        if (globalState.getSchema().getTables(t -> t.isView()).isEmpty()) {
            throw new IgnoreMeException();
        }
        StringBuilder sb = new StringBuilder("DROP VIEW ");
        if (Randomly.getBoolean()) {
            sb.append("IF EXISTS ");
        }

        sb.append(globalState.getSchema().getRandomTableOrBailout(t -> t.isView()).getName());
        return new SQLQueryAdapter(sb.toString(), null, true);
    }

}
