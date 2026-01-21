package com.onepipe.data.repositories;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.Parent;
import com.onepipe.data.entities.Payment;
import com.onepipe.data.entities.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByParent(Parent parent);

    List<Payment> findByStudent(Student student);

    List<Payment> findByBranch(Branch branch);

    Optional<Payment> findByTransactionRef(String transactionRef);
}
