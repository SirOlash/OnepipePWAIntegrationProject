package com.onepipe.service;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.User;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.UserRepository;
import com.onepipe.dtos.response.CreateBranchResponse;
import com.onepipe.integration.OnepipeClient;
import com.onepipe.dtos.request.CreateBranchRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BranchServiceTest {

    @Mock private BranchRepository branchRepository;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private OnepipeClient onePipeClient;
    @InjectMocks
    private BranchService branchService;

    @Test
    void testCreateBranch_Success() {
        // 1. SETUP
        CreateBranchRequest request = new CreateBranchRequest();
        request.setAdminEmail("admin@greenfield.com");
        request.setAdminPassword("password123");
        request.setBusinessName("Greenfield High");
        request.setBusinessShortName("GRN");

        // Mock dependencies
        when(userRepository.existsByEmail(request.getAdminEmail())).thenReturn(false);
        when(passwordEncoder.encode(request.getAdminPassword())).thenReturn("encrypted_pass");

        // Mock User Save
        User savedUser = new User();
        savedUser.setEmail(request.getAdminEmail());
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        // Mock OnePipe Client (Return a fake Merchant ID)
        when(onePipeClient.createMerchant(any(), anyString())).thenReturn("MERCH_ID_123");

        // Mock Branch Save
        when(branchRepository.save(any(Branch.class))).thenAnswer(i -> {
            Branch b = (Branch) i.getArguments()[0];
            b.setId(1L); // Simulate DB generating ID
            return b;
        });

        // 2. EXECUTE
        CreateBranchResponse response = branchService.createBranch(request);

        // 3. VERIFY
        // Verify we called OnePipe
        verify(onePipeClient).createMerchant(eq(request), anyString());

        // Verify response contains correct data
        Assertions.assertEquals("MERCH_ID_123", response.getBillerCode());
        Assertions.assertEquals("Greenfield High", response.getBusinessName());

        System.out.println("✅ Branch Creation Logic Verified");
    }
}