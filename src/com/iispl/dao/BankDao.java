package com.iispl.dao;

import java.util.List;

import com.iispl.entity.Bank;

/**
 * BankDao — Interface for bank table operations.
 */
public interface BankDao {
    Bank findById(Long bankId);
    Bank findByName(String bankName);
    List<Bank> findAll();
}
