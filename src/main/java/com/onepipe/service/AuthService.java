package com.onepipe.service;

import com.onepipe.config.JwtService;
import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.Parent;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.ParentRepository;
import com.onepipe.data.repositories.UserRepository;
import com.onepipe.dtos.request.LoginRequest;
import com.onepipe.dtos.response.LoginResponse;
import com.onepipe.exceptions.customExceptions.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final ParentRepository parentRepository;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public LoginResponse login(LoginRequest loginRequest) {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(loginRequest.getEmail(),
                loginRequest.getPassword())
        );

        var user = userRepository.findByEmail(loginRequest.getEmail())
                .orElseThrow(() -> new UserNotFoundException("User with email " + loginRequest.getEmail() + " not found"));

        var userDetails = org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPassword())
                .roles(user.getRole().name())
                .build();

        var token = jwtService.generateToken(userDetails);

        String name = "Admin";
        Long branchId = null;

        if (user.getRole() == Role.BRANCH_ADMIN) {
            Branch branch = branchRepository.findByAdminUser(user).orElse(null);
            if (branch != null) {
                branchId = branch.getId();
                name = branch.getBusinessName();
            }
        } else if (user.getRole() == Role.PARENT) {
            Parent parent = parentRepository.findByUser(user).orElse(null);
            if (parent != null) {
                name = parent.getFullName();
            }
        }

        return LoginResponse.builder()
                .token(token)
                .email(user.getEmail())
                .role(user.getRole())
                .firstName(name)
                .branchId(branchId)
                .build();
    }
}
