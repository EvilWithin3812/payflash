package com.payflash.sale;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    /** Sales that have not ended yet (upcoming or live), soonest first. */
    @Query("select s from Sale s where s.endsAt > :now order by s.startsAt, s.id")
    List<Sale> findNotEnded(@Param("now") Instant now);

    List<Sale> findTop50ByOrderByStartsAtDesc();
}
