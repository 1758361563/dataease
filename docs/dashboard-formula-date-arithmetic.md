# 仪表板共享字段日期运算兼容

周月报的“周环期”使用 `DATE_SUB([周期初],7)`，“MTD同期”使用 `ADD_MONTHS([MTD期初],-12)`。这些是公式函数写法，直接作为 MySQL 函数输出会报错：MySQL 的 DATE_SUB 第二个参数需要 INTERVAL，ADD_MONTHS 也不能直接调用。

参数绑定后，共享字段公式编译器现在转换为现有数据库方言流程支持的表达式：

- `DATE_SUB(date, days)` → `TIMESTAMPADD(DAY,-(days),date)`。
- `ADD_MONTHS(date, months)` → `TIMESTAMPADD(MONTH,(months),date)`。

支持嵌套 IF/AND 和共享字段依赖，保留正负偏移、月末截断与空日期传播行为。字符串中的函数名称不会被替换。已有 INTERVAL 形式不由这次两参数天数兼容转换改写。无须改写仪表板已保存的公式或数据库结构；需重新构建并更新 Java 后端。

验证包含参数绑定、嵌套表达式、字符串保护、参数数量、Calcite MySQL SQL 生成，以及实际 MySQL 只读执行的周边界、闰日、月末和期间 SUM。另读取本地周月报保存配置，完整展开三个指标依赖链，在样例日期与业绩上验证结果。

实际页面另发现共享指标的 `sort=null` 会被透视表误当作有效排序，调用 `toUpperCase()` 中断绘制。前端现将 null、缺省和空字符串按“无排序”处理（使用副本，不改保存配置），后端新共享字段默认 `sort=none`。`pivotNullableSort.test.cjs` 覆盖空排序及显式指标、维度、自定义、总计排序；已在本地浏览器确认“SL业绩情况”的表头和三个指标恢复显示。
