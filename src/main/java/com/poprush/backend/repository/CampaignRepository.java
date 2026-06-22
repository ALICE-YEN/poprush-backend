// repository 代表這裡放「資料庫操作相關的程式」
package com.poprush.backend.repository; // 宣告這個檔案所在的 package

import com.poprush.backend.entity.Campaign; // 匯入 Campaign Entity，Repository 會操作 Product 這張資料表對應的 Java 物件
import com.poprush.backend.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository; // JpaRepository 是 Spring Data JPA 提供的基本 Repository 介面，內建很多常用 DB 操作（findAll、findById、save、deleteById...）
import org.springframework.data.jpa.repository.Lock; // @Lock 用來告訴 JPA：這個查詢要使用哪一種資料庫 Lock
import org.springframework.data.jpa.repository.Query; // @Query 用來自訂 JPQL 查詢語句

import jakarta.persistence.LockModeType; // JPA 定義的 Lock 類型。PESSIMISTIC_WRITE 代表悲觀寫入鎖，底層通常會轉成 SELECT ... FOR UPDATE
import java.util.Optional; // 查詢結果可能存在，也可能不存在

// 兩個泛型。Campaign：這個 Repository 操作的是 Campaign entity。Long：Campaign 的 primary key 型別是 Long
public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    // 搶購時鎖住這場 Campaign，避免多個 request 同時扣同一份 stock
    @Lock(LockModeType.PESSIMISTIC_WRITE) // 告訴 JPA：這個查詢要加上悲觀寫入鎖
    @Query("SELECT c FROM Campaign c WHERE c.id = :id") // 自訂查詢 Campaign，JPQL 寫的是 Entity 名稱 Campaign，c 是 Campaign 的別名，:id 是方法參數傳進來的活動 id
    Optional<Campaign> findByIdForUpdate(Long id); // 自定義 findByIdForUpdate。根據活動 id 查詢 Campaign，並對該筆 Campaign 加上 pessimistic lock，這個方法會在 transaction 期間鎖住查到的 campaign row，transaction commit 或 rollback 後，lock 才會釋放
}
