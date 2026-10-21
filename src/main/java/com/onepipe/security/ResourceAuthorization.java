package com.onepipe.security;

import com.onepipe.data.entities.*;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.*;
import com.onepipe.exceptions.customExceptions.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

@Component
@RequiredArgsConstructor
public class ResourceAuthorization {
    private final UserRepository users;
    private final ParentRepository parents;
    private final BranchRepository branches;
    private final StudentRepository students;
    private final PaymentRepository payments;

    public Optional<User> currentUserIfAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        try {
            User user = users.findByEmailIgnoreCase(authentication.getName())
                    .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Authentication required"));
            if (user.getRole() == null) {
                throw denied();
            }
            return Optional.of(user);
        } catch (IncorrectResultSizeDataAccessException exception) {
            throw denied();
        }
    }

    public User requireUser() {
        return currentUserIfAuthenticated()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Authentication required"));
    }

    public void requireSuperAdmin() {
        if (requireUser().getRole() != Role.SUPER_ADMIN) {
            throw denied();
        }
    }

    public Parent ownParent() {
        Actor actor = actor();
        if (actor.user().getRole() != Role.PARENT) {
            throw denied();
        }
        return actor.parent();
    }

    public Branch branch(Long id) {
        Actor actor = actor();
        if (actor.user().getRole() == Role.SUPER_ADMIN) {
            return branches.findById(id).orElseThrow(ResourceNotFoundException::new);
        }
        if (actor.branch() == null || !sameId(actor.branch(), id)) {
            throw denied();
        }
        return actor.branch();
    }

    public Student student(Long id) {
        return student(actor(), id);
    }

    public Student ownStudent(Long id) {
        Actor actor = actor();
        if (actor.parent() == null) {
            throw denied();
        }
        return student(actor, id);
    }

    private Student student(Actor actor, Long id) {
        Student student = students.findById(id).orElseThrow(ResourceNotFoundException::new);
        checkStudent(actor, student);
        return student;
    }

    public Payment payment(String providerId) {
        Actor actor = actor();
        Payment payment;
        try {
            payment = payments.findByOnePipePaymentId(providerId).orElseThrow(ResourceNotFoundException::new);
        } catch (IncorrectResultSizeDataAccessException exception) {
            throw denied();
        }
        checkPayment(actor, payment);
        return payment;
    }

    public void checkPayments(List<Payment> records) {
        Actor actor = actor();
        records.forEach(payment -> checkPayment(actor, payment));
    }

    public List<Student> studentsForParentEmail(String email) {
        Actor actor = actor();
        if (email == null || email.isBlank()) {
            throw denied();
        }
        List<Student> result;
        if (actor.user().getRole() == Role.PARENT) {
            if (!actor.user().getEmail().equalsIgnoreCase(email.trim())) {
                throw denied();
            }
            result = students.findByParent(actor.parent());
        } else if (actor.user().getRole() == Role.BRANCH_ADMIN) {
            result = students.findByBranchAndParent_User_EmailIgnoreCase(actor.branch(), email.trim());
            if (result.isEmpty()) {
                throw new ResourceNotFoundException();
            }
        } else {
            Parent parent;
            try {
                parent = parents.findByUser_EmailIgnoreCase(email.trim()).orElseThrow(ResourceNotFoundException::new);
            } catch (IncorrectResultSizeDataAccessException exception) {
                throw denied();
            }
            result = students.findByParent(parent);
        }
        result.forEach(student -> checkStudent(actor, student));
        return result;
    }

    public Predicate<Branch> branchDetailsScope() {
        Optional<User> caller = currentUserIfAuthenticated();
        if (caller.isEmpty() || caller.get().getRole() == Role.PARENT) {
            return branch -> false;
        }
        if (caller.get().getRole() == Role.SUPER_ADMIN) {
            return branch -> true;
        }
        Branch own = associatedBranch(caller.get());
        return branch -> sameId(own, branch.getId());
    }

    private Actor actor() {
        User user = requireUser();
        if (user.getRole() == Role.PARENT) {
            Parent parent;
            try {
                parent = parents.findByUser(user).orElseThrow(ResourceAuthorization::denied);
            } catch (IncorrectResultSizeDataAccessException exception) {
                throw denied();
            }
            if (parent.getUser() == null || !sameId(user, parent.getUser().getId())) {
                throw denied();
            }
            return new Actor(user, parent, null);
        }
        return new Actor(user, null, user.getRole() == Role.BRANCH_ADMIN ? associatedBranch(user) : null);
    }

    private Branch associatedBranch(User user) {
        try {
            return branches.findByAdminUser(user).orElseThrow(ResourceAuthorization::denied);
        } catch (IncorrectResultSizeDataAccessException exception) {
            throw denied();
        }
    }

    private void checkStudent(Actor actor, Student student) {
        if (student.getParent() == null || student.getBranch() == null || student.getParent().getUser() == null
                || student.getParent().getUser().getRole() != Role.PARENT) {
            throw denied();
        }
        if (actor.user().getRole() == Role.SUPER_ADMIN) {
            return;
        }
        if (actor.parent() != null && sameId(actor.parent(), student.getParent().getId())) {
            return;
        }
        if (actor.branch() != null && sameId(actor.branch(), student.getBranch().getId())) {
            return;
        }
        throw denied();
    }

    private void checkPayment(Actor actor, Payment payment) {
        Student student = payment.getStudent();
        if (student == null || payment.getParent() == null || payment.getBranch() == null
                || student.getParent() == null || student.getBranch() == null
                || !sameId(payment.getParent(), student.getParent().getId())
                || !sameId(payment.getBranch(), student.getBranch().getId())) {
            throw denied();
        }
        checkStudent(actor, student);
    }

    private static boolean sameId(BaseEntity entity, Long id) {
        return entity.getId() != null && entity.getId().equals(id);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access denied");
    }

    private record Actor(User user, Parent parent, Branch branch) { }
}
