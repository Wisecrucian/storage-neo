package one.idsstorage.clickhouse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines "hot" attributes that should be materialized as separate ClickHouse columns
 * for efficient filtering and sorting without parsing the attrs Map on every query.
 *
 * Configure in application.yml:
 * <pre>
 * app:
 *   hot-attributes:
 *     columns:
 *       - name: attr_lang
 *         source-key: lang
 *         ch-type: "LowCardinality(String)"
 *       - name: attr_score
 *         source-key: score
 *         ch-type: Int32
 *         expression: "toInt32OrZero(attrs['score'])"
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "app.hot-attributes")
public class HotAttributeConfig {

    private List<HotColumn> columns = new ArrayList<>();

    public List<HotColumn> getColumns() {
        return columns;
    }

    public void setColumns(List<HotColumn> columns) {
        this.columns = columns;
    }

    public static class HotColumn {
        private String name;
        private String sourceKey;
        private String chType = "LowCardinality(String)";
        private String expression;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getSourceKey() { return sourceKey; }
        public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }

        public String getChType() { return chType; }
        public void setChType(String chType) { this.chType = chType; }

        public String getExpression() { return expression; }
        public void setExpression(String expression) { this.expression = expression; }

        public String buildMaterializeExpression() {
            if (expression != null && !expression.isBlank()) {
                return expression;
            }
            return "attrs['" + sourceKey + "']";
        }

        public String toAlterSql(String table) {
            return "ALTER TABLE " + table +
                    " ADD COLUMN IF NOT EXISTS " + name + " " + chType +
                    " MATERIALIZED " + buildMaterializeExpression();
        }
    }
}
