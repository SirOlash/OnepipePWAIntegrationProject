package com.onepipe.data.entities;

import com.onepipe.data.enums.Title;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "parents")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Parent extends BaseEntity{
    @OneToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    private Title title;

    private String firstName;
    private String surname;
    private String phoneNumber;

    private String bankAccountNumber;
    private String bankCode;
}
