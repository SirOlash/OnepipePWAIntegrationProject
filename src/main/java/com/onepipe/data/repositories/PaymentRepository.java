package com.onepipe.data.repositories;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.Parent;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.Student;
import com.onepipe.data.enums.PaymentCategory;
import com.onepipe.data.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.onePipePaymentId = :providerId")
    Optional<Payment> findLockedByOnePipePaymentId(@Param("providerId") String providerId);

    @Query("select p.id from Payment p")
    List<Long> findAllPaymentIds();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.transactionRef = :ref")
    Optional<Payment> findLockedByTransactionRef(@Param("ref") String transactionRef);

    List<Payment> findByParent(Parent parent);

    Optional<Payment> findByOnePipePaymentId(String onePipePaymentId);

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.status = 'ACTIVE'")
    Long countActivePayments();

    List<Payment> findByStudent(Student student);

    List<Payment> findByBranch(Branch branch);
    // Add this method
    List<Payment> findByStatus(PaymentStatus status);

    @Query("SELECT COALESCE(SUM(c.confirmedAmount), 0) FROM PaymentCollection c")
    BigDecimal calculateTotalRevenue();

    Optional<Payment> findByTransactionRef(String transactionRef);

    Optional<Payment> findFirstByStudentAndCategoryAndStatusIn(
            Student student,
            PaymentCategory category,
            List<PaymentStatus> statuses
    );
}
