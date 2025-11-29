package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.CommissionRange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface CommissionRangeRepository extends JpaRepository<CommissionRange, Long> {
    Optional<CommissionRange> findByMinAmount(BigDecimal minAmount);
    List<CommissionRange> findAllByOrderByMinAmountAsc();
}

