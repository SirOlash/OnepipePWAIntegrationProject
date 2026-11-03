package com.onepipe.security;

import com.onepipe.data.entities.*;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ResourceAuthorizationTest {
    private final UserRepository users = mock(UserRepository.class);
    private final ParentRepository parents = mock(ParentRepository.class);
    private final BranchRepository branches = mock(BranchRepository.class);
    private final StudentRepository students = mock(StudentRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ResourceAuthorization access = new ResourceAuthorization(users, parents, branches, students, payments);

    @AfterEach
    void clearPrincipal() { SecurityContextHolder.clearContext(); }

    @Test
    void missingAuthenticationFailsBeforeResourceQueries() {
        assertThatThrownBy(() -> access.student(42L)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verifyNoInteractions(users, parents, branches, students, payments);
    }

    @Test
    void theFrameworkAnonymousTokenNeverResolvesAnAccount() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "test", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(access.currentUserIfAuthenticated()).isEmpty(); verifyNoInteractions(users);
    }

    @Test
    void databaseRoleWinsOverPrincipalAuthorities() {
        authenticate();
        User user = User.builder().email("parent@example.invalid").role(Role.PARENT).build(); user.setId(1L);
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        assertThatThrownBy(access::requireSuperAdmin).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(parents, branches, students, payments);
    }

    @Test
    void ambiguousUserIdentitiesFailClosed() {
        authenticate();
        when(users.findByEmailIgnoreCase("parent@example.invalid"))
                .thenThrow(new IncorrectResultSizeDataAccessException(1, 2));
        assertThatThrownBy(access::requireUser).isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied").hasNoCause();
    }

    @Test
    void parentAssociationIsRequiredBeforeLookingUpARequestedStudent() {
        authenticate();
        User user = User.builder().email("parent@example.invalid").role(Role.PARENT).build(); user.setId(1L);
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> access.student(42L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(students, payments);
    }

    @Test
    void missingAndAmbiguousBranchAssociationsFailBeforeTargetLookup() {
        authenticate();
        User user = User.builder().email("parent@example.invalid").role(Role.BRANCH_ADMIN).build(); user.setId(1L);
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> access.branch(42L)).isInstanceOf(AccessDeniedException.class);
        when(branches.findByAdminUser(user)).thenThrow(new IncorrectResultSizeDataAccessException(1, 2));
        assertThatThrownBy(() -> access.branch(42L)).isInstanceOf(AccessDeniedException.class);
        verify(branches, never()).findById(anyLong());
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "parent@example.invalid", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }
}
