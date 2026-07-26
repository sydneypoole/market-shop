package com.marketshop.infrastructure.persistence.mapper;

import com.marketshop.infrastructure.persistence.model.CustomerPersistenceModels.AddressRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface CustomerMapper {

    @Select("""
            SELECT id, recipient_name, phone_masked AS phone, province, city, district,
                   detail_address, postal_code, is_default AS default_address, version
            FROM customer_address
            WHERE user_id = #{userId} AND deleted_at IS NULL
            ORDER BY is_default DESC, id DESC
            """)
    List<AddressRow> addresses(@Param("userId") long userId);

    @Update("""
            UPDATE customer_address
            SET is_default = 0, version = version + 1
            WHERE user_id = #{userId} AND deleted_at IS NULL AND is_default = 1
              AND (#{excludeAddressId} IS NULL OR id != #{excludeAddressId})
            """)
    int clearDefault(
            @Param("userId") long userId,
            @Param("excludeAddressId") Long excludeAddressId
    );

    @Insert("""
            INSERT INTO customer_address
                (user_id, recipient_name, phone_masked, province, city, district,
                 detail_address, postal_code, is_default)
            VALUES
                (#{userId}, #{row.recipientName}, #{row.phone}, #{row.province}, #{row.city}, #{row.district},
                 #{row.detailAddress}, #{row.postalCode}, #{row.defaultAddress})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insert(@Param("userId") long userId, @Param("row") AddressRow row);

    @Update("""
            UPDATE customer_address
            SET recipient_name = #{row.recipientName}, phone_masked = #{row.phone},
                province = #{row.province}, city = #{row.city}, district = #{row.district},
                detail_address = #{row.detailAddress}, postal_code = #{row.postalCode},
                is_default = #{row.defaultAddress}, version = version + 1
            WHERE id = #{row.id} AND user_id = #{userId} AND version = #{row.version}
              AND deleted_at IS NULL
            """)
    int update(@Param("userId") long userId, @Param("row") AddressRow row);

    @Update("""
            UPDATE customer_address
            SET deleted_at = CURRENT_TIMESTAMP(3), is_default = 0, version = version + 1
            WHERE id = #{addressId} AND user_id = #{userId} AND version = #{version}
              AND deleted_at IS NULL
            """)
    int delete(
            @Param("userId") long userId,
            @Param("addressId") long addressId,
            @Param("version") int version
    );
}
