package com.iispl.entity;

/**
 * Processable interface — any class implementing this can be validated and
 * processed by the settlement pipeline.
 *
 * T2 (Transaction domain) will implement this on Transaction subclasses.
 */
public interface Processable {

	/**
	 * Validate the object's fields before processing. Should throw
	 * IllegalStateException if validation fails.
	 */
	void validate();

	/**
	 * Execute the core processing logic for this object.
	 */
	void process();
}
