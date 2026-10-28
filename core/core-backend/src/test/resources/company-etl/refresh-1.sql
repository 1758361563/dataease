-- refresh.sql：qiwei_new_bi.全渠道_销售 日常滚动窗口（由 etls/全渠道-销售/sql/run.sql 机械变换）
-- 窗口字段：orderDate；窗口 [@win_start, @win_end)，窗口外数据不动。
-- 隔离 smoke 未把 @smoke_ok 换成 YES 之前，本文件会在门禁处失败。
-- 执行前：python scripts/05_check_formal_gate.py --kind refresh

SET @win_start := '__REQUIRED_WINDOW_START__';
SET @win_end   := '__REQUIRED_WINDOW_END__';
SET @smoke_ok  := '__SAFETY_SMOKE_PASSED__';
SELECT IF(
  @smoke_ok = 'YES'
  AND @win_start REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
  AND @win_end REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
  AND @win_start < @win_end,
  1,
  (SELECT * FROM `qiwei_new_bi`.`__STOP__refresh_blocked_until_smoke_and_window`)
) AS refresh_gate_ok;

DROP TABLE IF EXISTS `qiwei_new_bi`.`全渠道_销售_refresh_stage`;
CREATE TABLE `qiwei_new_bi`.`全渠道_销售_refresh_stage` (
`orderDate` DATETIME NULL,
    `storeCode` VARCHAR(255) NULL,
    `storeName` VARCHAR(255) NULL,
    `channelCode` VARCHAR(255) NULL,
    `channelName` VARCHAR(255) NULL,
    `parentAreaCode` VARCHAR(255) NULL,
    `parentAreaName` VARCHAR(255) NULL,
    `areaCode` VARCHAR(255) NULL,
    `areaName` VARCHAR(255) NULL,
    `orderNo` VARCHAR(255) NULL,
    `orderStatusName` VARCHAR(255) NULL,
    `thirdPartycode` VARCHAR(255) NULL,
    `firstClassesCode` VARCHAR(255) NULL,
    `firstClassesName` VARCHAR(255) NULL,
    `secondClassesCode` VARCHAR(255) NULL,
    `secondClassesName` VARCHAR(255) NULL,
    `classesCode` VARCHAR(255) NULL,
    `classesName` VARCHAR(255) NULL,
    `taste_name` VARCHAR(255) NULL,
    `brandCode` VARCHAR(255) NULL,
    `seriesCode` VARCHAR(255) NULL,
    `seriesName` VARCHAR(255) NULL,
    `productNo` VARCHAR(255) NULL,
    `thirdProductCode` VARCHAR(255) NULL,
    `comProductNo` VARCHAR(255) NULL,
    `platComProductNo` VARCHAR(255) NULL,
    `barCode` VARCHAR(255) NULL,
    `shortCode` VARCHAR(255) NULL,
    `specification` VARCHAR(255) NULL,
    `productName` VARCHAR(255) NULL,
    `orderQuantity` DECIMAL(20,4) NULL,
    `retailPrice` DECIMAL(20,4) NULL,
    `retailMoney` DECIMAL(20,4) NULL,
    `activeCode` VARCHAR(255) NULL,
    `activeName` VARCHAR(255) NULL,
    `salesPerson` VARCHAR(255) NULL,
    `paymentTime` DATETIME NULL,
    `actualPrice` DECIMAL(20,4) NULL,
    `actualMoney` DECIMAL(20,4) NULL,
    `discount` VARCHAR(255) NULL,
    `productType` VARCHAR(255) NULL,
    `buyerMessage` VARCHAR(255) NULL,
    `sailerMessage` VARCHAR(255) NULL,
    `platformNickname` VARCHAR(255) NULL,
    `memberName` VARCHAR(255) NULL,
    `memberCode` VARCHAR(255) NULL,
    `createByCode` VARCHAR(255) NULL,
    `createByName` VARCHAR(255) NULL,
    `name_total` VARCHAR(255) NULL,
    `province_name` VARCHAR(255) NULL,
    `city_name` VARCHAR(255) NULL,
    `street` VARCHAR(255) NULL,
    `customer_name` VARCHAR(255) NULL,
    `county_name` VARCHAR(255) NULL,
    `零元订单` VARCHAR(255) NULL,
    `品类-系列-规格` VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `qiwei_new_bi`.`全渠道_销售_refresh_stage` (
`orderDate`,
    `storeCode`,
    `storeName`,
    `channelCode`,
    `channelName`,
    `parentAreaCode`,
    `parentAreaName`,
    `areaCode`,
    `areaName`,
    `orderNo`,
    `orderStatusName`,
    `thirdPartycode`,
    `firstClassesCode`,
    `firstClassesName`,
    `secondClassesCode`,
    `secondClassesName`,
    `classesCode`,
    `classesName`,
    `taste_name`,
    `brandCode`,
    `seriesCode`,
    `seriesName`,
    `productNo`,
    `thirdProductCode`,
    `comProductNo`,
    `platComProductNo`,
    `barCode`,
    `shortCode`,
    `specification`,
    `productName`,
    `orderQuantity`,
    `retailPrice`,
    `retailMoney`,
    `activeCode`,
    `activeName`,
    `salesPerson`,
    `paymentTime`,
    `actualPrice`,
    `actualMoney`,
    `discount`,
    `productType`,
    `buyerMessage`,
    `sailerMessage`,
    `platformNickname`,
    `memberName`,
    `memberCode`,
    `createByCode`,
    `createByName`,
    `name_total`,
    `province_name`,
    `city_name`,
    `street`,
    `customer_name`,
    `county_name`,
    `零元订单`,
    `品类-系列-规格`
)
SELECT
    `orderDate`,
    `storeCode`,
    `storeName`,
    `channelCode`,
    `channelName`,
    `parentAreaCode`,
    `parentAreaName`,
    `areaCode`,
    `areaName`,
    `orderNo`,
    `orderStatusName`,
    `thirdPartycode`,
    `firstClassesCode`,
    `firstClassesName`,
    `secondClassesCode`,
    `secondClassesName`,
    `classesCode`,
    `classesName`,
    `taste_name`,
    `brandCode`,
    `seriesCode`,
    `seriesName`,
    `productNo`,
    `thirdProductCode`,
    `comProductNo`,
    `platComProductNo`,
    `barCode`,
    `shortCode`,
    `specification`,
    `productName`,
    `orderQuantity`,
    `retailPrice`,
    `retailMoney`,
    `activeCode`,
    `activeName`,
    `salesPerson`,
    `paymentTime`,
    `actualPrice`,
    `actualMoney`,
    `discount`,
    `productType`,
    `buyerMessage`,
    `sailerMessage`,
    `platformNickname`,
    `memberName`,
    `memberCode`,
    `createByCode`,
    `createByName`,
    `name_total`,
    `province_name`,
    `city_name`,
    `street`,
    `customer_name`,
    `county_name`,
    `零元订单`,
    `品类-系列-规格`
FROM (
    -- 筛选：筛选数据行（保留在观远原本的管道位置）
    SELECT *
    FROM (
        -- 计算列：添加计算列
        SELECT
            n_id_1640847968025.*,
            CASE WHEN `actualMoney`=0 THEN '零元' ELSE '非零' END AS `零元订单`,
            CONCAT(`classesName`, '-', `taste_name`, '-', `specification`) AS `品类-系列-规格`
        FROM (
            -- 多行拼接：行拼接 → UNION ALL（2 个分支）
            -- 各分支列不一致，缺列已补 NULL
            -- 分支：销售订单数据-线下
            SELECT
                n_id_1640847941601.`orderDate`,
                n_id_1640847941601.`storeCode`,
                n_id_1640847941601.`storeName`,
                n_id_1640847941601.`channelCode`,
                n_id_1640847941601.`channelName`,
                n_id_1640847941601.`parentAreaCode`,
                n_id_1640847941601.`parentAreaName`,
                n_id_1640847941601.`areaCode`,
                n_id_1640847941601.`areaName`,
                n_id_1640847941601.`orderNo`,
                n_id_1640847941601.`orderStatusName`,
                n_id_1640847941601.`thirdPartycode`,
                n_id_1640847941601.`firstClassesCode`,
                n_id_1640847941601.`firstClassesName`,
                n_id_1640847941601.`secondClassesCode`,
                n_id_1640847941601.`secondClassesName`,
                n_id_1640847941601.`classesCode`,
                n_id_1640847941601.`classesName`,
                n_id_1640847941601.`taste_name`,
                n_id_1640847941601.`brandCode`,
                n_id_1640847941601.`seriesCode`,
                n_id_1640847941601.`seriesName`,
                n_id_1640847941601.`productNo`,
                n_id_1640847941601.`thirdProductCode`,
                n_id_1640847941601.`comProductNo`,
                n_id_1640847941601.`platComProductNo`,
                n_id_1640847941601.`barCode`,
                n_id_1640847941601.`shortCode`,
                n_id_1640847941601.`specification`,
                n_id_1640847941601.`productName`,
                n_id_1640847941601.`orderQuantity`,
                n_id_1640847941601.`retailPrice`,
                n_id_1640847941601.`retailMoney`,
                n_id_1640847941601.`activeCode`,
                n_id_1640847941601.`activeName`,
                n_id_1640847941601.`salesPerson`,
                n_id_1640847941601.`paymentTime`,
                n_id_1640847941601.`actualPrice`,
                n_id_1640847941601.`actualMoney`,
                n_id_1640847941601.`discount`,
                n_id_1640847941601.`productType`,
                n_id_1640847941601.`buyerMessage`,
                n_id_1640847941601.`sailerMessage`,
                n_id_1640847941601.`platformNickname`,
                n_id_1640847941601.`memberName`,
                n_id_1640847941601.`memberCode`,
                n_id_1640847941601.`createByCode`,
                n_id_1640847941601.`createByName`,
                n_id_1640847941601.`name_total`,
                NULL AS `province_name`,
                NULL AS `city_name`,
                NULL AS `street`,
                NULL AS `customer_name`,
                NULL AS `county_name`
            FROM (
                -- 输入：销售订单数据-线下（MYSQL 叶子）
                SELECT *
                FROM `qiwei_new_bi`.`销售订单数据_线下` AS src_n_id_1640847941601
                WHERE `orderDate` >= @win_start
                  AND `orderDate` < @win_end
                  AND `orderStatusName` <> '交易关闭'
            ) AS n_id_1640847941601

            UNION ALL

            -- 分支：销售订单数据
            SELECT
                n_id_1640847942524.`orderDate`,
                n_id_1640847942524.`storeCode`,
                n_id_1640847942524.`storeName`,
                n_id_1640847942524.`channelCode`,
                n_id_1640847942524.`channelName`,
                n_id_1640847942524.`parentAreaCode`,
                n_id_1640847942524.`parentAreaName`,
                n_id_1640847942524.`areaCode`,
                n_id_1640847942524.`areaName`,
                n_id_1640847942524.`orderNo`,
                n_id_1640847942524.`orderStatusName`,
                n_id_1640847942524.`thirdPartycode`,
                n_id_1640847942524.`firstClassesCode`,
                n_id_1640847942524.`firstClassesName`,
                n_id_1640847942524.`secondClassesCode`,
                n_id_1640847942524.`secondClassesName`,
                n_id_1640847942524.`classesCode`,
                n_id_1640847942524.`classesName`,
                n_id_1640847942524.`taste_name`,
                n_id_1640847942524.`brandCode`,
                n_id_1640847942524.`seriesCode`,
                n_id_1640847942524.`seriesName`,
                n_id_1640847942524.`productNo`,
                n_id_1640847942524.`thirdProductCode`,
                n_id_1640847942524.`comProductNo`,
                n_id_1640847942524.`platComProductNo`,
                n_id_1640847942524.`barCode`,
                n_id_1640847942524.`shortCode`,
                n_id_1640847942524.`specification`,
                n_id_1640847942524.`productName`,
                n_id_1640847942524.`orderQuantity`,
                n_id_1640847942524.`retailPrice`,
                n_id_1640847942524.`retailMoney`,
                n_id_1640847942524.`activeCode`,
                n_id_1640847942524.`activeName`,
                n_id_1640847942524.`salesPerson`,
                n_id_1640847942524.`paymentTime`,
                n_id_1640847942524.`actualPrice`,
                n_id_1640847942524.`actualMoney`,
                n_id_1640847942524.`discount`,
                n_id_1640847942524.`productType`,
                n_id_1640847942524.`buyerMessage`,
                n_id_1640847942524.`sailerMessage`,
                n_id_1640847942524.`platformNickname`,
                n_id_1640847942524.`memberName`,
                n_id_1640847942524.`memberCode`,
                n_id_1640847942524.`createByCode`,
                n_id_1640847942524.`createByName`,
                NULL AS `name_total`,
                n_id_1640847942524.`province_name`,
                n_id_1640847942524.`city_name`,
                n_id_1640847942524.`street`,
                n_id_1640847942524.`customer_name`,
                n_id_1640847942524.`county_name`
            FROM (
                -- 输入：销售订单数据（源查询内联，不读 qiwei_new_bi 物理表）
                -- 日期窗口在 d_online_order.order_date；外层 orderDate / 交易关闭过滤保留。
                SELECT *
                FROM (
                    select
                                doo.order_date orderDate,
                                doo.store_code storeCode,
                                doo.store_name storeName,
                                sd.dic_code channelCode,
                                sd.dic_value channelName,
                                map.code parentAreaCode,
                                map.name parentAreaName,
                                ma.code areaCode,
                                ma.name areaName,
                                doo.order_no orderNo,
                                case
                                    when doo.status = 'not_audited' then '待审核'
                                    when doo.status = 'not_send' then '待发货'
                                    when doo.status = 'sending' then '发货中'
                                    when doo.status = 'sended' then '已发货'
                                    when doo.status = 'yes_cancel' then '交易关闭'
                                    when doo.status = 'audited' then '交易成功'
                                    when doo.status = 'ban_send' then '禁止发货'
                    								when doo.status = 'success' then '交易成功'
                                end as orderStatusName,
                                doo.thirdparty_code thirdPartycode,
                                product.firstClassesCode firstClassesCode,
                                product.firstClassesName firstClassesName,
                                product.secondClassesCode secondClassesCode,
                                product.secondClassesName secondClassesName,
                                product.thirdClassesCode classesCode,
                                product.thirdClassesName classesName,
                                product.taste_name taste_name,
                                mp.brand_code brandCode,
                                mp.series_code seriesCode,
                                mp.series_name seriesName,
                                dood.product_no productNo,
                                dood.thirdProductCode thirdProductCode,
                                ifnull(dood.com_product_no,'--') as comProductNo,
                                '--' as platComProductNo,
                                product.barCode barCode,
                                product.shortCode shortCode,
                                product.specification specification,
                                dood.product_name productName,
                                round(IFNULL(dood.quantity,0),2) orderQuantity,
                                round(IFNULL(dood.retail_price,0),2) retailPrice,
                                round(IFNULL((dood.retail_price * dood.quantity),0),2) retailMoney,
                                '--' as activeCode,
                                '--' as activeName,
                                '--' as salesPerson,
                                doo.paymentTime paymentTime,
                                round(IFNULL((dood.actrueMoney/dood.quantity),0),2) actualPrice,
                                round(IFNULL(dood.actrueMoney,0),2) actualMoney,
                                concat(round(IFNULL(((dood.actrueMoney/dood.quantity)/dood.retail_price)*100,0)),'%') discount,
                                if(dood.isGift= 0 ,'正品','赠品') productType,
                                doo.buyer_message buyerMessage,
                                doo.sailer_message sailerMessage,
                                doo.vip platformNickname,
                                '--' as memberName,
                                '--' as memberCode,
                                doo.create_by_code as createByCode,
                                doo.create_by_name as createByName,
                                doo.province_name as province_name,
                                doo.city_name as city_name,
                                doo.street as street,
                                doo.customer_name as customer_name,
                                doo.county_name as county_name
                            from (
                                select * from com_newretail_distributionservice.d_online_order
                                where
                                    is_delete = 0
                                    -- 窗口下推：orderDate = d_online_order.order_date
                                    and order_date >= @win_start
                                    and order_date < @win_end
                            ) doo
                            inner join com_newretail_distributionservice.d_online_order_detail dood on dood.order_code = doo.code and dood.is_delete = 0
                            left join com_newretail_maindataservice.m_customers mc on mc.code = doo.store_code and mc.is_delete = 0
                            left join com_newretail_authorityservice.s_dictionary sd on mc.channel_code = sd.code and sd.is_delete = 0
                            left join com_newretail_maindataservice.m_areas ma on ma.code = mc.area_code and ma.is_delete = 0
                            left join com_newretail_maindataservice.m_areas map on map.code = ma.parent_code and map.is_delete = 0
                            left join com_newretail_maindataservice.m_products mp on mp.code = dood.product_code and mp.is_delete = 0
                            LEFT JOIN (
                                SELECT
                                    t.product_no,
                                    t.name name,
                    					  t.taste_name,	
                                	  c0.code firstClassesCode,
                                    c0.name firstClassesName,
                                    c1.code secondClassesCode,
                                    c1.name secondClassesName,
                                    c2.code thirdClassesCode,
                                    c2.name thirdClassesName,
                                    t.barcode barCode,
                                    s1.short_code shortCode,
                                    CONCAT( s.specs, s.specs_unit_name ) specification
                                FROM com_newretail_maindataservice.m_products t
                                LEFT JOIN com_newretail_maindataservice.m_product_classes c2 ON t.classes_code = c2.code AND c2.level_code = 2
                                LEFT JOIN com_newretail_maindataservice.m_product_classes c1 ON c2.parent_code = c1.code AND c1.level_code = 1
                                LEFT JOIN com_newretail_maindataservice.m_product_classes c0 ON c1.parent_code = c0.code AND c0.level_code = 0
                                LEFT JOIN com_newretail_maindataservice.m_product_specs s ON t.code = s.code
                                LEFT JOIN com_newretail_maindataservice.m_product_brand_taste s1 on s1.item_type = 0 and s1.code = t.brand_code
                            ) product ON mp.product_no = product.product_no
                            left join com_newretail_maindataservice.m_product_prices mpp on mpp.product_code = mp.code and mpp.is_delete = 0
                ) AS src_n_id_1640847942524
                WHERE `orderDate` >= @win_start
                  AND `orderDate` < @win_end
                  AND `orderStatusName` <> '交易关闭'
            ) AS n_id_1640847942524
        ) AS n_id_1640847968025
    ) AS n_id_1644292423654
    WHERE (`orderStatusName` <> '交易关闭')
) AS n_id_1649321016657
;

-- 校验暂存表通过后再执行事务；失败则 ROLLBACK，不要继续 DROP stage。
START TRANSACTION;
DELETE FROM `qiwei_new_bi`.`全渠道_销售`
  WHERE `orderDate` >= @win_start AND `orderDate` < @win_end;
INSERT INTO `qiwei_new_bi`.`全渠道_销售` (
`orderDate`,
    `storeCode`,
    `storeName`,
    `channelCode`,
    `channelName`,
    `parentAreaCode`,
    `parentAreaName`,
    `areaCode`,
    `areaName`,
    `orderNo`,
    `orderStatusName`,
    `thirdPartycode`,
    `firstClassesCode`,
    `firstClassesName`,
    `secondClassesCode`,
    `secondClassesName`,
    `classesCode`,
    `classesName`,
    `taste_name`,
    `brandCode`,
    `seriesCode`,
    `seriesName`,
    `productNo`,
    `thirdProductCode`,
    `comProductNo`,
    `platComProductNo`,
    `barCode`,
    `shortCode`,
    `specification`,
    `productName`,
    `orderQuantity`,
    `retailPrice`,
    `retailMoney`,
    `activeCode`,
    `activeName`,
    `salesPerson`,
    `paymentTime`,
    `actualPrice`,
    `actualMoney`,
    `discount`,
    `productType`,
    `buyerMessage`,
    `sailerMessage`,
    `platformNickname`,
    `memberName`,
    `memberCode`,
    `createByCode`,
    `createByName`,
    `name_total`,
    `province_name`,
    `city_name`,
    `street`,
    `customer_name`,
    `county_name`,
    `零元订单`,
    `品类-系列-规格`
)
SELECT `orderDate`,
    `storeCode`,
    `storeName`,
    `channelCode`,
    `channelName`,
    `parentAreaCode`,
    `parentAreaName`,
    `areaCode`,
    `areaName`,
    `orderNo`,
    `orderStatusName`,
    `thirdPartycode`,
    `firstClassesCode`,
    `firstClassesName`,
    `secondClassesCode`,
    `secondClassesName`,
    `classesCode`,
    `classesName`,
    `taste_name`,
    `brandCode`,
    `seriesCode`,
    `seriesName`,
    `productNo`,
    `thirdProductCode`,
    `comProductNo`,
    `platComProductNo`,
    `barCode`,
    `shortCode`,
    `specification`,
    `productName`,
    `orderQuantity`,
    `retailPrice`,
    `retailMoney`,
    `activeCode`,
    `activeName`,
    `salesPerson`,
    `paymentTime`,
    `actualPrice`,
    `actualMoney`,
    `discount`,
    `productType`,
    `buyerMessage`,
    `sailerMessage`,
    `platformNickname`,
    `memberName`,
    `memberCode`,
    `createByCode`,
    `createByName`,
    `name_total`,
    `province_name`,
    `city_name`,
    `street`,
    `customer_name`,
    `county_name`,
    `零元订单`,
    `品类-系列-规格` FROM `qiwei_new_bi`.`全渠道_销售_refresh_stage`;
COMMIT;

DROP TABLE IF EXISTS `qiwei_new_bi`.`全渠道_销售_refresh_stage`;
