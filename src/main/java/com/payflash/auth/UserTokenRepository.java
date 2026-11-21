package com.payflash.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    /** Locked, so two concurrent uses of the same link cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from UserToken t where t.tokenHash = :hash")
    Optional<UserToken> lockByHash(String hash);

    Optional<UserToken> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(Long userId, UserToken.Purpose purpose);

    @Modifying
    @Query("delete from UserToken t where t.userId = :userId and t.purpose = :purpose")
    void deleteAllFor(Long userId, UserToken.Purpose purpose);

    @Modifying
    @Query("delete from UserToken t where t.userId = :userId")
    void deleteAllFor(Long userId);
}
