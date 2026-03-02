package com.jpmc.midascore;

import com.jpmc.midascore.foundation.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionListener {
    private static final Logger logger = LoggerFactory.getLogger(TransactionListener.class);
    private final com.jpmc.midascore.component.DatabaseConduit databaseConduit;
    private final org.springframework.web.client.RestTemplate restTemplate;

    public TransactionListener(com.jpmc.midascore.component.DatabaseConduit databaseConduit,
            org.springframework.web.client.RestTemplate restTemplate) {
        this.databaseConduit = databaseConduit;
        this.restTemplate = restTemplate;
    }

    @KafkaListener(topics = "${general.kafka-topic}", groupId = "midas-core-group")
    public void listen(Transaction transaction) {
        logger.info("Processing transaction: {}", transaction);

        com.jpmc.midascore.entity.UserRecord sender = databaseConduit.findById(transaction.getSenderId());
        com.jpmc.midascore.entity.UserRecord recipient = databaseConduit.findById(transaction.getRecipientId());

        if (validate(sender, recipient, transaction)) {
            com.jpmc.midascore.foundation.Incentive incentive = restTemplate.postForObject(
                    "http://localhost:8080/incentive", transaction, com.jpmc.midascore.foundation.Incentive.class);
            float incentiveAmount = incentive != null ? incentive.getAmount() : 0;

            sender.setBalance(sender.getBalance() - transaction.getAmount());
            recipient.setBalance(recipient.getBalance() + transaction.getAmount() + incentiveAmount);

            databaseConduit.save(sender);
            databaseConduit.save(recipient);
            databaseConduit.saveTransaction(
                    new com.jpmc.midascore.entity.TransactionRecord(sender, recipient, transaction.getAmount(),
                            incentiveAmount));

            logger.info("Transaction processed successfully with incentive: {}", incentiveAmount);
        } else {
            logger.warn("Transaction validation failed: {}", transaction);
        }
    }

    private boolean validate(com.jpmc.midascore.entity.UserRecord sender,
            com.jpmc.midascore.entity.UserRecord recipient, Transaction transaction) {
        return sender != null && recipient != null && sender.getBalance() >= transaction.getAmount();
    }
}