package io.dataease.company.bi.dataupdate;

import java.math.*;
import java.sql.Types;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static io.dataease.company.bi.dataupdate.UpdateDefinition.require;

/** Strict conversion shared by preview and execution; never rounds or replaces invalid input with zero. */
public final class SheetValues {
    private SheetValues() { }
    public static Object convert(Object raw,SheetSource.Field field,UpdateInspection.Column column,List<String> nulls,int row) {
        String value=raw==null?null:raw.toString();
        if(value==null || value.isEmpty() || nulls.contains(value)) {
            require(!field.required() && column.nullable(),"第 "+row+" 行「"+field.source()+"」不能为空"); return null;
        }
        try {
            return switch(column.jdbcType()) {
                case Types.DECIMAL,Types.NUMERIC -> {
                    BigDecimal n=new BigDecimal(value.strip()).setScale(column.scale(),RoundingMode.UNNECESSARY);
                    require(n.precision()<=column.precision(),"decimal 超出精度"); yield n;
                }
                case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT -> {
                    BigInteger n=new BigDecimal(value.strip()).toBigIntegerExact();
                    int bits=switch(column.jdbcType()){case Types.TINYINT->8;case Types.SMALLINT->16;case Types.INTEGER->32;default->64;};
                    boolean unsigned=column.type().toUpperCase(Locale.ROOT).contains("UNSIGNED");
                    require(n.compareTo(unsigned?BigInteger.ZERO:BigInteger.ONE.shiftLeft(bits-1).negate())>=0
                            && n.compareTo(BigInteger.ONE.shiftLeft(unsigned?bits:bits-1).subtract(BigInteger.ONE))<=0,"整数超出范围"); yield new BigDecimal(n);
                }
                case Types.DOUBLE,Types.FLOAT,Types.REAL -> {
                    double n=Double.parseDouble(value.strip()); require(Double.isFinite(n),"数值超出范围"); yield n;
                }
                case Types.BOOLEAN,Types.BIT -> {
                    require(Set.of("0","1","true","false").contains(value.toLowerCase(Locale.ROOT)),"布尔值须为 true/false 或 1/0");
                    yield value.equals("1") || value.equalsIgnoreCase("true");
                }
                case Types.DATE -> java.sql.Date.valueOf(LocalDate.parse(value.strip(),format(field,"yyyy-MM-dd")));
                case Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE -> java.sql.Timestamp.valueOf(LocalDateTime.parse(value.strip(),format(field,"yyyy-MM-dd HH:mm:ss")));
                case Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR -> {
                    require(column.precision()<=0 || value.codePointCount(0,value.length())<=column.precision(),"文本超过目标长度"); yield value;
                }
                default -> throw new IllegalArgumentException("目标类型不支持："+column.type());
            };
        } catch(Exception e) { throw new UpdateDefinition.ValidationException("第 "+row+" 行「"+field.source()+"」无法写入 "+column.type()+"：请检查类型、精度或日期格式"); }
    }
    private static DateTimeFormatter format(SheetSource.Field field,String fallback) {
        String pattern=field.dateFormat()==null || field.dateFormat().isBlank()?fallback:field.dateFormat();
        return DateTimeFormatter.ofPattern(pattern.replace("yyyy","uuuu")).withResolverStyle(ResolverStyle.STRICT);
    }
    public static void mapping(SheetSource source,List<String> headers,UpdateInspection.Structure structure) {
        Set<String> supplied=new HashSet<>();
        for(var field:source.fields()) {
            require(headers.contains(field.source()),"缺少约定源字段："+field.source());
            var target=structure.columns().stream().filter(c->c.name().equals(field.target())).findFirst().orElseThrow(()->new UpdateDefinition.ValidationException("目标字段不存在："+field.target()));
            require(!target.generated(),"自动生成字段无需映射："+target.name()); supplied.add(target.name());
        }
        for(var column:structure.columns()) require(supplied.contains(column.name()) || column.nullable() || column.generated() || column.defaultValue()!=null,"目标必填字段未映射："+column.name());
    }
}
