package com.onepipe.data.repositories;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.Parent;
import com.onepipe.data.entities.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    List<Student> findByParent(Parent parent);
    List<Student> findByBranch(Branch branch);
    Optional<Student> findByStudentRegId(String studentRegId);

}
