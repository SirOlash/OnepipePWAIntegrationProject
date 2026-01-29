package com.onepipe.data.repositories;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.Parent;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.Student;
import com.onepipe.data.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByParent(Parent parent);

    Optional<Payment> findByOnePipePaymentId(String onePipePaymentId);

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.status = 'ACTIVE'")
    Long countActivePayments();

    List<Payment> findByStudent(Student student);

    List<Payment> findByBranch(Branch branch);
    // Add this method
    List<Payment> findByStatus(PaymentStatus status);

    @Query("SELECT COALESCE(SUM(p.totalAmount), 0) FROM Payment p WHERE p.status = 'SUCCESSFUL'")
    BigDecimal calculateTotalRevenue();

    Optional<Payment> findByTransactionRef(String transactionRef);
}
