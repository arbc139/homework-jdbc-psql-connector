import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ManipulateHandler {
  enum ConditionErrorCode {
    ERROR, EARLY_FINISHED, SUCCESS;
  }

  enum ArithmeticOp {
    INVALID(-1, Condition.Operator.INVALID),
    EQ(1, Condition.Operator.EQ),
    GT(2, Condition.Operator.GT),
    LT(3, Condition.Operator.LT),
    GTE(4, Condition.Operator.GTE),
    LTE(5, Condition.Operator.LTE),
    NEQ(6, Condition.Operator.NEQ),
    LIKE(7, Condition.Operator.LIKE);

    private final int code;
    private final Condition.Operator op;
    ArithmeticOp(int code, Condition.Operator op) {
      this.code = code;
      this.op = op;
    }

    public int getCode() {
      return code;
    }
    public Condition.Operator getOp() {
      return op;
    }

    public static ArithmeticOp getArithmeticOp(int code) {
      for (ArithmeticOp op : ArithmeticOp.values()) {
        if (op.code == code) {
          return op;
        }
      }
      return INVALID;
    }
  }

  enum LogicalOp {
    INVALID(-1, Condition.Operator.INVALID),
    AND(1, Condition.Operator.AND),
    OR(2, Condition.Operator.OR),
    FINISH(3, Condition.Operator.INVALID);

    private final int code;
    private final Condition.Operator op;
    LogicalOp(int code, Condition.Operator op) {
      this.code = code;
      this.op = op;
    }

    public int getCode() {
      return code;
    }
    public Condition.Operator getOp() {
      return op;
    }

    public static LogicalOp getLogicalOp(int code) {
      for (LogicalOp op : LogicalOp.values()) {
        if (op.code == code) {
          return op;
        }
      }
      return INVALID;
    }
  }

  public ManipulateHandler(Statement st, PsqlConnection conn) {
    this.st = st;
    this.conn = conn;
  }

  public void run() throws SQLException {
    while (true) {
      Labeler.ConsoleLabel.MANIPULATE_DATA_INIT.print();
      Scanner input = new Scanner(System.in);
      int code;

      try {
        code = Integer.valueOf(input.nextLine());
      } catch (NumberFormatException e) {
        Labeler.ConsoleLabel.COMMON_TRY_AGAIN.println();
        continue;
      }
      ManipulateInst inst = ManipulateInst.getManipulateInst(code);
      switch (inst) {
        case SHOW_TABLES: {
          Labeler.ConsoleLabel.MANIPULATE_DATA_SHOW_TABLE_HEADER.println();
          Schema schema = createSchema("pg_catalog", "pg_tables");
          if (schema == null) {
            Labeler.ConsoleLabel.COMMON_TABLE_NAME_NOT_EXISTS.println();
            Labeler.ConsoleLabel.MANIPULATE_DATA_DESCRIBE_FAILURE.println();
            continue;
          }
          runShowTables(schema, "pg_catalog", "pg_tables");
          break;
        }
        case DESCRIBE_TABLE: {
          Labeler.ConsoleLabel.MANIPULATE_DATA_DESCRIBE_SPECIFY_TABLE_NAME.print();
          String tableName = input.nextLine();
          Schema schema = createSchema(conn.getBaseSchemaName(), tableName);
          if (schema == null) {
            Labeler.ConsoleLabel.COMMON_TABLE_NAME_NOT_EXISTS.println();
            Labeler.ConsoleLabel.MANIPULATE_DATA_DESCRIBE_FAILURE.println();
            continue;
          }
          Labeler.ConsoleLabel.MANIPULATE_DATA_DESCRIBE_HEADER.println();
          for (String row : schema.getDescribes()) {
            System.out.println(row);
          }
          break;
        }
        case SELECT: {
          Labeler.ConsoleLabel.MANIPULATE_DATA_SELECT_SPECIFY_TABLE_NAME.print();
          String tableName = input.nextLine();
          // Get Schema
          Schema schema = createSchema(conn.getBaseSchemaName(), tableName);
          if (schema == null) {
            Labeler.ConsoleLabel.COMMON_TABLE_NAME_NOT_EXISTS.println();
            Labeler.ConsoleLabel.MANIPULATE_DATA_SELECT_FAILURE.println();
            continue;
          }
          runSelect(input, schema, conn.getBaseSchemaName(), tableName);
          break;
        }
        case INSERT: {
          break;
        }
        case DELETE: {
          break;
        }
        case UPDATE: {
          break;
        }
        case DROP_TABLE: {
          break;
        }
        case BACK_TO_MAIN: {
          return;
        }
        case INVALID: {
          Labeler.ConsoleLabel.COMMON_TRY_AGAIN.println();
          continue;
        }
      }
      System.out.println();
    }
  }

  private Schema createSchema(String baseSchemaName, String tableName) {
    try {
      return Schema.getSchema(baseSchemaName, tableName, st);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private void runShowTables(Schema schema, String baseSchemaName, String tableName) {
    Query query = new Query.Builder()
        .setType(Query.Type.SELECT)
        .setBaseSchemaName(baseSchemaName)
        .setSchema(schema)
        .setTableName(tableName)
        .addSelectedColumn("tablename")
        .addCondition("schemaname", Condition.Operator.EQ, conn.getBaseSchemaName())
        .build();
    try {
      ResultSet rs = st.executeQuery(query.toString());
      while (rs.next()) {
        System.out.println(rs.getString(1));
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private void runSelect(Scanner input, Schema schema, String baseSchemaName, String tableName) {
    Query.Builder builder = new Query.Builder()
        .setType(Query.Type.SELECT)
        .setBaseSchemaName(baseSchemaName)
        .setTableName(tableName)
        .setSchema(schema);
    // Selected columns
    Labeler.ConsoleLabel.MANIPULATE_DATA_SELECT_SPECIFY_COLUMNS.print();
    String rawSelectedColumns = input.nextLine();
    if (rawSelectedColumns.equals("*")) {
      builder.addSelectedColumn("*");
    } else {
      String[] selectedColumns = rawSelectedColumns.split(",");
      for (String selectedColumn : selectedColumns) {
        String col = selectedColumn.trim();
        if (!builder.schema.isContain(col)) {
          Labeler.ConsoleLabel.MANIPULATE_DATA_COMMON_COLUMN_NOT_EXIST.println();
          Labeler.ConsoleLabel.MANIPULATE_DATA_SELECT_FAILURE.println();
          return;
        }
        builder.addSelectedColumn(selectedColumn.trim());
      }
    }
    // Add conditions
    ConditionErrorCode err = addConditions(input, builder);
    if (err == ConditionErrorCode.ERROR) {
      Labeler.ConsoleLabel.MANIPULATE_DATA_COMMON_COLUMN_NOT_EXIST.println();
      Labeler.ConsoleLabel.MANIPULATE_DATA_SELECT_FAILURE.println();
      return;
    }
    // Add orders
    while (true) {
      Labeler.ConsoleLabel.MANIPULATE_DATA_SELECT_ORDER_COLUMN.print();
      String rawOrderCols = input.nextLine();
      if (rawOrderCols.isEmpty()) {
        break;
      }
      List<String> orderCols = Arrays.stream(rawOrderCols.split(","))
          .map(String::trim)
          .collect(Collectors.toList());
      if (orderCols.stream().anyMatch(col -> !builder.schema.isContain(col))) {
        Labeler.ConsoleLabel.MANIPULATE_DATA_SELECT_ORDER_COLUMN_METHOD_INVALID.println();
        continue;
      }
      Labeler.ConsoleLabel.MANIPULATE_DATA_SELECT_ORDER_METHOD.print();
      String rawOrderCriterias = input.nextLine();
      List<Query.Order> orderCriterias;
      if (!rawOrderCriterias.isEmpty()) {
        orderCriterias = Arrays.stream(rawOrderCriterias.split(","))
            .map(String::trim)
            .map(Query.Order::getOrder)
            .collect(Collectors.toList());
      } else {
        orderCriterias = orderCols.stream()
            .map(col -> Query.Order.ASC)
            .collect(Collectors.toList());
      }
      if (orderCols.size() != orderCriterias.size()) {
        Labeler.ConsoleLabel.MANIPULATE_DATA_SELECT_ORDER_COLUMN_METHOD_INVALID.println();
        continue;
      }
      if (orderCriterias.stream().anyMatch(order -> order == Query.Order.INVALID)) {
        Labeler.ConsoleLabel.MANIPULATE_DATA_SELECT_ORDER_COLUMN_METHOD_INVALID.println();
        continue;
      }
      for (int i = 0; i < orderCols.size(); ++i) {
        String orderCol = orderCols.get(i);
        Query.Order orderCriteria = orderCriterias.get(i);
        builder.addOrder(orderCol, orderCriteria);
      }
      break;
    }
    Query query = builder.build();
    StringBuilder strBuilder = new StringBuilder()
        .append("======================================================\n")
        .append(String.join(" | ", builder.schema.columnOrder)).append("\n")
        .append("======================================================\n");
    System.out.println(strBuilder);
    List<String> rows = new ArrayList<>();
    try {
      ResultSet rs = st.executeQuery(query.toString());
      while (rs.next()) {
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); ++i) {
          cols.add(rs.getString(i));
        }
        rows.add(String.join(", ", cols));
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    printRowsByChunks(input, rows, 10);
  }

  private void printRowsByChunks(Scanner input, List<String> rows, int chunkSize) {
    AtomicInteger counter = new AtomicInteger();
    Collection<List<String>> chunks = rows.stream()
        .collect(Collectors.groupingBy(row -> counter.getAndIncrement() / chunkSize))
        .values();
    boolean isPressNeeded = true;
    if (chunks.size() <= 1) {
      isPressNeeded = false;
    }
    int iterate_counter = 0;
    for (List<String> row_chunk : chunks) {
      row_chunk.forEach(System.out::println);
      if ((iterate_counter < chunks.size() - 1) && isPressNeeded) {
        Labeler.ConsoleLabel.MANIPULATE_DATA_COMMON_PRESS_ENTER.print();
        input.nextLine();
      }
      iterate_counter++;
    }
    System.out.println(String.format("<%d rows selected>", rows.size()));
  }

  private ConditionErrorCode addConditions(Scanner input, Query.Builder builder) {
    LogicalOp legacyLogop = LogicalOp.INVALID;
    while (true) {
      Labeler.ConsoleLabel.MANIPULATE_DATA_COMMON_SPECIFY_CONDITION_COLUMN.print();
      // Condition Column Name
      String condCol = input.nextLine();
      if (condCol.isEmpty()) {
        return ConditionErrorCode.EARLY_FINISHED;
      }
      if (!builder.schema.isContain(condCol)) {
        return ConditionErrorCode.ERROR;
      }
      // Condition Arithmetic Operator
      ArithmeticOp arop;
      while (true) {
        try {
          Labeler.ConsoleLabel.MANIPULATE_DATA_COMMON_SPECIFY_CONDITION_ARITHMETIC_OPERATOR.print();
          int code = Integer.valueOf(input.nextLine());
          arop = ArithmeticOp.getArithmeticOp(code);
          if (arop != ArithmeticOp.INVALID) {
            break;
          }
        } catch (NumberFormatException e) {
          Labeler.ConsoleLabel.COMMON_TRY_AGAIN.println();
          continue;
        }
        Labeler.ConsoleLabel.COMMON_TRY_AGAIN.println();
      }
      // Condition Value
      Labeler.ConsoleLabel.MANIPULATE_DATA_COMMON_SPECIFY_CONDITION_VALUE.print();
      System.out.print(String.format(
          " (%s %s ?) : ", condCol, arop.op.getLabel()));
      String value = input.nextLine();
      // Condition Logical Operator
      LogicalOp logop;
      while (true) {
        try {
          Labeler.ConsoleLabel.MANIPULATE_DATA_COMMON_SPECIFY_CONDITION_LOGICAL_OPERATOR.print();
          int code = Integer.valueOf(input.nextLine());
          logop = LogicalOp.getLogicalOp(code);
          if (logop != LogicalOp.INVALID) {
            break;
          }
        } catch (NumberFormatException e) {
          Labeler.ConsoleLabel.COMMON_TRY_AGAIN.println();
          continue;
        }
        Labeler.ConsoleLabel.COMMON_TRY_AGAIN.println();
      }

      if (legacyLogop == LogicalOp.INVALID) {
        builder.addCondition(condCol, arop.op, value);
      } else {
        builder.addCondition(legacyLogop.op, condCol, arop.op, value);
      }

      if (logop == LogicalOp.FINISH) {
        break;
      } else {
        legacyLogop = logop;
      }
    }
    return ConditionErrorCode.SUCCESS;
  }

  private Statement st;
  private PsqlConnection conn;
}
