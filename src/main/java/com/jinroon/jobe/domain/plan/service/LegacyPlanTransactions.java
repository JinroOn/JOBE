package com.jinroon.jobe.domain.plan.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short, independent reservation/publication transactions. Never wrap provider I/O. */
@Component
public class LegacyPlanTransactions {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entities;
    private final TransactionTemplate transactions;
    public LegacyPlanTransactions(PlatformTransactionManager manager){
        transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public <T>T execute(Supplier<T> work){
        return transactions.execute(status->{
            // An OSIV EntityManager can outlive the provider wait. Never publish from its stale identity map.
            entities.clear();
            return work.get();
        });
    }
}
