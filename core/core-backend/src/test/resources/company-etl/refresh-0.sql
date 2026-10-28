-- refresh.sql：qiwei_new_bi.销售订单数据_线下 日常滚动窗口（由 etls/销售订单数据-线下/sql/run.sql 机械变换）
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

DROP TABLE IF EXISTS `qiwei_new_bi`.`销售订单数据_线下_refresh_stage`;
CREATE TABLE `qiwei_new_bi`.`销售订单数据_线下_refresh_stage` (
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
    `name_total` VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `qiwei_new_bi`.`销售订单数据_线下_refresh_stage` (
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
    `name_total`
)
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
            '已完成' as orderStatusName,
            doo.source_no thirdPartycode,
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
            '--' as thirdProductCode,
            '--' as comProductNo,
            '--' as platComProductNo,
            product.barCode barCode,
            product.shortCode shortCode,
            product.specification specification,
            dood.product_name productName,
            round(IFNULL(dood.quantity,0),2) orderQuantity,
            round(IFNULL(dood.retail_price,0),2) retailPrice,
            round(IFNULL((dood.retail_price * dood.quantity),0),2) retailMoney,
            ifnull(dood.main_code,'--') as activeCode,
            ifnull(dood.activity_name,'--') as activeName,
            ifnull(doo.pperator_name,ifnull(doo.order_persion,'--')) as salesPerson,
            doo.order_date paymentTime,
            round(IFNULL(dood.purchase_price,0),2) actualPrice,
            round(IFNULL(dood.apportion_amount,0),2) actualMoney,
            concat(round((IFNULL(dood.apportion_amount/dood.quantity/dood.retail_price,0)*100),0),'%') discount,
            if(LOCATE('TZZK',dood.product_no) > 0,'促销品',if((dood.purchase_price * dood.quantity/dood.quantity)=0,'赠品','正品')) productType,
            '--' as buyerMessage,
            '--' as sailerMessage,
            '--' as platformNickname,
            ifnull(doo.member_name,'--') as memberName,
            ifnull(doo.member_id,'--') as memberCode,
            doo.order_persion as createByCode,
            ifnull(ibe.employee_name, doo.create_by_name) as createByName,
						dood.name_total as name_total
        from (
            select * from com_newretail_distributionservice.d_order
            where
                is_delete = 0
                and service_type = '2'
                -- 窗口下推：线下门店 orderDate = d_order.order_date
                and order_date >= @win_start
                and order_date < @win_end
        ) doo
        inner join com_newretail_distributionservice.d_order_detail dood on dood.order_code = doo.code and dood.is_delete = 0
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
        left join com_newretail_maindataservice.if_bk_employee ibe on ibe.is_delete = 0 and ibe.employee_code = doo.order_persion
        union all
        select
            ifnull(doo.out_library_time,s.update_date) orderDate,
            doo.store_code storeCode,
            doo.store_name storeName,
            'jdzy' channelCode,
            '京东自营' channelName,
            map.code parentAreaCode,
            map.name parentAreaName,
            ma.code areaCode,
            ma.name areaName,
            doo.order_no orderNo,
            '已完成' as orderStatusName,
            doo.source_no thirdPartycode,
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
            '--' as thirdProductCode,
            '--' as comProductNo,
            '--' as platComProductNo,
            product.barCode barCode,
            product.shortCode shortCode,
            product.specification specification,
            dood.product_name productName,
            round(IFNULL(dood.quantity,0),2) orderQuantity,
            round(IFNULL(dood.retail_price,0),2) retailPrice,
            round(IFNULL((dood.retail_price * dood.quantity),0),2) retailMoney,
            ifnull(dood.main_code,'--') as activeCode,
            ifnull(dood.activity_name,'--') as activeName,
            ifnull(doo.pperator_name,ifnull(doo.order_persion,'--')) as salesPerson,
            doo.order_date paymentTime,
            round(IFNULL(dood.retail_price,0),2) actualPrice,
            round(IFNULL(dood.total_amount,0),2) actualMoney,
            concat(round((IFNULL(dood.apportion_amount/dood.quantity/dood.retail_price,0)*100),0),'%') discount,
            if(LOCATE('TZZK',dood.product_no) > 0,'促销品',if((dood.purchase_price * dood.quantity/dood.quantity)=0,'赠品','正品')) productType,
            '--' as buyerMessage,
            '--' as sailerMessage,
            '--' as platformNickname,
            ifnull(doo.member_name,'--') as memberName,
            ifnull(doo.member_id,'--') as memberCode,
            doo.order_persion as createByCode,
            ifnull(ibe.employee_name, doo.create_by_name) as createByName,
						dood.name_total as name_total
        from (
            select * from com_newretail_distributionservice.d_order
            where
                is_delete = 0
--                 and service_type = '2'
								AND order_type = 'platform_order'
								AND service_type = 0
        ) doo
        inner join com_newretail_distributionservice.d_order_detail dood on dood.order_code = doo.code and dood.is_delete = 0
        INNER JOIN com_newretail_distributionservice.d_output s ON doo.code = s.code AND s.is_delete = 0
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
        left join com_newretail_maindataservice.if_bk_employee ibe on ibe.is_delete = 0 and ibe.employee_code = doo.order_persion
        -- 窗口下推：京东自营 orderDate = IFNULL(out_library_time, d_output.update_date)
        -- 必须写在本分支（d_output 已 JOIN）；IFNULL 拆成可走索引的写法
        where
            (
                doo.out_library_time IS NOT NULL
                AND doo.out_library_time >= @win_start
                AND doo.out_library_time < @win_end
            )
            OR
            (
                doo.out_library_time IS NULL
                AND s.update_date >= @win_start
                AND s.update_date < @win_end
            )
;

-- 校验暂存表通过后再执行事务；失败则 ROLLBACK，不要继续 DROP stage。
START TRANSACTION;
DELETE FROM `qiwei_new_bi`.`销售订单数据_线下`
  WHERE `orderDate` >= @win_start AND `orderDate` < @win_end;
INSERT INTO `qiwei_new_bi`.`销售订单数据_线下` (
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
    `name_total`
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
    `name_total` FROM `qiwei_new_bi`.`销售订单数据_线下_refresh_stage`;
COMMIT;

DROP TABLE IF EXISTS `qiwei_new_bi`.`销售订单数据_线下_refresh_stage`;
