package com.onepipe.service;

import com.onepipe.data.entities.Branch;
import com.onepipe.data.entities.User;
import com.onepipe.data.enums.Role;
import com.onepipe.data.repositories.BranchRepository;
import com.onepipe.data.repositories.UserRepository;
import com.onepipe.dtos.request.CreateBranchRequest;
import com.onepipe.dtos.response.CreateBranchResponse;
import com.onepipe.integration.OnepipeClient;
import lombok.RequiredArgsConstructor;
import com.onepipe.security.ResourceAuthorization;
import com.onepipe.exceptions.customExceptions.ResourceConflictException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BranchService {

    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final OnepipeClient onePipeClient;
    private final ResourceAuthorization authorization;

    @Transactional
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CreateBranchResponse createBranch(CreateBranchRequest request) {
        authorization.requireSuperAdmin();

        if (userRepository.existsByEmailIgnoreCase(request.getAdminEmail())) {
            throw new ResourceConflictException("Request conflicts with existing data");
        }

        User newAdmin = User.builder()
                .email(request.getAdminEmail().toLowerCase())
                .password(passwordEncoder.encode(request.getAdminPassword()))
                .role(Role.BRANCH_ADMIN)
                .build();

        User savedAdmin = userRepository.save(newAdmin);

        String myRequestRef = UUID.randomUUID().toString();
        String myTransactionRef = "TXN-" + System.currentTimeMillis();

        String onePipeBillerCode = onePipeClient.createMerchant(request, myTransactionRef, myRequestRef);

        Branch newBranch = Branch.builder()
                .adminUser(savedAdmin)
                .businessName(request.getBusinessName())
                .businessShortName(request.getBusinessShortName())
                .businessAddress(request.getAddress())
                .rcNumber(request.getRcNumber())
                .tin(request.getTin())
                .contactFirstName(request.getContactFirstName())
                .contactSurname(request.getContactSurname())
                .contactEmail(request.getAdminEmail())
                .contactPhoneNumber(request.getContactPhoneNumber())
                .whatsappNumber(request.getWhatsappNumber())
                .settlementAccountNumber(request.getSettlementAccountNumber())
                .settlementBankCode(request.getSettlementBankCode())
                .requestRef(myRequestRef)
                .transactionRef(myTransactionRef)
                .billerCode(onePipeBillerCode)
                .build();

        Branch savedBranch = branchRepository.save(newBranch);

        userRepository.save(savedAdmin);

        return mapToResponse(savedBranch);

    }

    @Transactional(readOnly = true)
    public List<CreateBranchResponse> getAllBranches() {
        Predicate<Branch> privateDetails = authorization.branchDetailsScope();
        return branchRepository.findAll()
                .stream()
                .map(branch -> privateDetails.test(branch) ? mapToResponse(branch) : mapPublicResponse(branch))
                .collect(Collectors.toList());
    }

    private CreateBranchResponse mapPublicResponse(Branch branch) {
        return CreateBranchResponse.builder().id(branch.getId()).businessName(branch.getBusinessName())
                .branchCode(branch.getBusinessShortName()).address(branch.getBusinessAddress()).build();
    }

    private CreateBranchResponse mapToResponse(Branch branch) {
        return CreateBranchResponse.builder()
                .id(branch.getId())
                .businessName(branch.getBusinessName())
                .billerCode(branch.getBillerCode())
                .branchCode(branch.getBusinessShortName())
                .address(branch.getBusinessAddress())
                .adminEmail(branch.getAdminUser() == null ? null : branch.getAdminUser().getEmail())
                .phoneNumber(branch.getWhatsappNumber())
                .contactPersonName(branch.getContactFirstName() + " " + branch.getContactSurname())
                .build();
    }
}
