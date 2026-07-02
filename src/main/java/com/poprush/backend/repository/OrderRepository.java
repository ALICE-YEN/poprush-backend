package com.poprush.backend.repository;

import com.poprush.backend.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdempotencyKeyAndUser_IdAndCampaign_Id(String idempotencyKey, Long userId, Long campaignId);
}
