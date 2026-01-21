package com.onepipe.data.repositories;

import com.onepipe.data.entities.Parent;
import com.onepipe.data.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ParentRepository extends JpaRepository<Parent, Long> {
    Optional<Parent> findByUser(User user);
    Optional<Parent> findByPhoneNumber(String phoneNumber);
    Optional<Parent> findByUser_Email(String email);

}
