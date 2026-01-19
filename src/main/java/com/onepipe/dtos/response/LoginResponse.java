package com.onepipe.dtos.response;

import com.onepipe.data.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponse {
    private String token;
    private String email;
    private Role role;
    private String firstName;
    private Long branchId;
}
