package sqlancer.common.log;

import java.io.PrintWriter;
import java.io.StringWriter;

import sqlancer.common.query.Query;
import sqlancer.common.query.SQLQueryAdapter;

public class SQLLoggableFactory extends LoggableFactory {

    @Override
    protected Loggable createLoggable(String input, String suffix) {

        boolean endsWithSemicolon = input.endsWith(";");
        boolean hasNewline = input.indexOf('\n') >= 0 || input.indexOf('\r') >= 0;
        boolean hasSuffix = suffix != null && !suffix.isEmpty();
        if (endsWithSemicolon && !hasNewline && !hasSuffix) {
            return new LoggedString(input);
        }

        StringBuilder sb = new StringBuilder(input.length() + 4 + (hasSuffix ? suffix.length() : 0));
        if (hasNewline) {

            int len = input.length();
            for (int i = 0; i < len; i++) {
                char c = input.charAt(i);
                if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else {
                    sb.append(c);
                }
            }
        } else {
            sb.append(input);
        }
        if (!endsWithSemicolon) {
            sb.append(';');
        }
        if (hasSuffix) {
            sb.append(suffix);
        }
        return new LoggedString(sb.toString());
    }

    @Override
    public SQLQueryAdapter getQueryForStateToReproduce(String queryString) {
        return new SQLQueryAdapter(queryString);
    }

    @Override
    public SQLQueryAdapter commentOutQuery(Query<?> query) {
        String queryString = query.getLogString();
        String newQueryString = "-- " + queryString;
        return new SQLQueryAdapter(newQueryString);
    }

    @Override
    protected Loggable infoToLoggable(String time, String databaseName, String databaseVersion, long seedValue) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- Time: ").append(time).append("\n");
        sb.append("-- Database: ").append(databaseName).append("\n");
        sb.append("-- Database version: ").append(databaseVersion).append("\n");
        sb.append("-- seed value: ").append(seedValue).append("\n");
        return new LoggedString(sb.toString());
    }

    @Override
    public Loggable convertStacktraceToLoggable(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return new LoggedString("--" + sw.toString().replace("\n", "\n--"));
    }
}
