package com.jinroon.jobe.common.domain;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

@Getter
@MappedSuperclass
public abstract class BaseEntitySupport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
