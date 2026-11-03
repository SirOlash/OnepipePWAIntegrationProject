package com.onepipe.data.repositories;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BranchRepository extends JpaRepository<Branch, Long> {

    Optional<Branch> findByAdminUser_EmailIgnoreCase(String email);
    Optional<Branch> findByAdminUser(User adminUser);

    List<Branch> findByRequestRefOrTransactionRefOrRcNumber(String requestRef, String transactionRef, String rcNumber);

    boolean existsByRequestRef(String requestRef);

}
