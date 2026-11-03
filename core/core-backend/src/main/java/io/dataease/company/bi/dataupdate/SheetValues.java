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
                    decimalPolicy(field,column);
                    int places=field.decimalPlaces()==null?column.scale():field.decimalPlaces();
                    RoundingMode mode=switch(Objects.toString(field.decimalMode(),"PRESERVE")) {
                        case "ROUND" -> RoundingMode.HALF_UP;
                        case "FLOOR" -> RoundingMode.FLOOR;
                        case "CEILING" -> RoundingMode.CEILING;
                        default -> RoundingMode.UNNECESSARY;
                    };
                    BigDecimal n=new BigDecimal(value.strip()).setScale(places,mode).setScale(column.scale(),RoundingMode.UNNECESSARY);
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
                case Types.DATE -> java.sql.Date.valueOf(date(value.strip(),field));
                case Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE -> java.sql.Timestamp.valueOf(LocalDateTime.parse(value.strip(),format(field,"yyyy-MM-dd HH:mm:ss")));
                case Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR -> {
                    require(column.precision()<=0 || value.codePointCount(0,value.length())<=column.precision(),"文本超过目标长度"); yield value;
                }
                default -> throw new IllegalArgumentException("目标类型不支持："+column.type());
            };
        } catch(Exception e) {
            String reason=column.jdbcType()==Types.DECIMAL || column.jdbcType()==Types.NUMERIC
                    ? "：请检查数值、处理方式和小数位数；目标精度为 ("+column.precision()+","+column.scale()+")，保留模式不舍入"
                    : "：请检查类型、精度或日期格式";
            throw new UpdateDefinition.ValidationException("第 "+row+" 行「"+field.source()+"」无法写入 "+column.type()+reason);
        }
    }
    private static void decimalPolicy(SheetSource.Field field,UpdateInspection.Column column) {
        require(field.decimalMode()==null || Set.of("PRESERVE","ROUND","FLOOR","CEILING").contains(field.decimalMode()),"数值处理方式无效");
        require(field.decimalPlaces()==null || field.decimalPlaces()>=0 && field.decimalPlaces()<=Math.min(30,column.scale()),"小数位数超过目标列上限");
    }
    private static DateTimeFormatter format(SheetSource.Field field,String fallback) {
        String pattern=field.dateFormat()==null || field.dateFormat().isBlank()?fallback:field.dateFormat();
        return DateTimeFormatter.ofPattern(pattern.replace("yyyy","uuuu")).withResolverStyle(ResolverStyle.STRICT);
    }
    private static LocalDate date(String value,SheetSource.Field field) {
        if(field.dateFormat()!=null && !field.dateFormat().isBlank())return LocalDate.parse(value,format(field,"yyyy-MM-dd"));
        // Only unambiguous year-first calendar dates. Never guess day/month order or serial numbers.
        String pattern=value.matches("[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}")?"uuuu-M-d"
                :value.matches("[0-9]{4}/[0-9]{1,2}/[0-9]{1,2}")?"uuuu/M/d":null;
        require(pattern!=null,"请选择源日期格式");
        return LocalDate.parse(value,DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT));
    }
    public static void mapping(SheetSource source,List<String> headers,UpdateInspection.Structure structure) {
        Set<String> supplied=new HashSet<>();
        for(var field:source.fields()) {
            require(headers.contains(field.source()),"缺少约定源字段："+field.source());
            var target=structure.columns().stream().filter(c->c.name().equals(field.target())).findFirst().orElseThrow(()->new UpdateDefinition.ValidationException("目标字段不存在："+field.target()));
            require(!target.generated(),"自动生成字段无需映射："+target.name()); supplied.add(target.name());
            if(target.jdbcType()==Types.DECIMAL || target.jdbcType()==Types.NUMERIC)decimalPolicy(field,target);
            else require(field.decimalMode()==null && field.decimalPlaces()==null,"数值转换仅适用于 DECIMAL 字段："+target.name());
        }
        for(var column:structure.columns()) require(supplied.contains(column.name()) || column.nullable() || column.generated() || column.defaultValue()!=null,"目标必填字段未映射："+column.name());
    }
}
