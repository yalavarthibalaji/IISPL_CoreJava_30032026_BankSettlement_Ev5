package com.iispl.banksettlement.dao;

import com.iispl.banksettlement.entity.Bank;
import java.util.List;

/**
 * BankDao — Interface for bank table operations.
 */
public interface BankDao {
    Bank findById(Long bankId);
    Bank findByName(String bankName);
    List<Bank> findAll();
}
