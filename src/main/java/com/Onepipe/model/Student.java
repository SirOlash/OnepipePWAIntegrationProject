package com.Onepipe.model;


import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "students")
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String studentId;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String parentName;

    @Column(nullable = false)
    private String parentPhoneNumber;

    @Column(nullable = false)
    private String parentEmail;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (this.studentId == null || this.studentId.isBlank()) {
            String shortId = UUID.randomUUID().toString().substring(0, 4);
            this.studentId = String.format("STU-%s-%s", Instant.now().toString().substring(0,10).replaceAll("-", ""), shortId);
        }
    }

    @Override
    public String toString() {
        return "Student{" +
                "id=" + id +
                ", studentId='" + studentId + '\'' +
                ", name='" + firstName + ' ' + lastName + '\'' +
                ", parent='" + parentName + '\'' +
                '}';
    }
}
