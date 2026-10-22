package com.diamond.shared;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;

@Configuration

public class Broker {
    public static final String EVENTS = "diamond.events";
    public static final String DEAD = "diamond.dead";
    public static final String ORDERS = "diamond.orders";
    public static final String NOTIFICATIONS = "diamond.notifications";

    @Bean Declarables eventTopology() {
        var events = new TopicExchange(EVENTS, true, false);
        var dead = new DirectExchange(DEAD, true, false);
        var orders = queue(ORDERS);
        var notifications = queue(NOTIFICATIONS);
        var ordersDead = QueueBuilder.durable(ORDERS + ".dlq").build();
        var notificationsDead = QueueBuilder.durable(NOTIFICATIONS + ".dlq").build();
        var market = QueueBuilder.durable("diamond.market").maxLength(1000).ttl(86400000).build();
        return new Declarables(events, dead, orders, notifications, ordersDead, notificationsDead, market,
            BindingBuilder.bind(orders).to(events).with("AuctionEnded"),
            BindingBuilder.bind(notifications).to(events).with("AuctionEnded"),
            BindingBuilder.bind(notifications).to(events).with("BidderOutbid"),
            BindingBuilder.bind(notifications).to(events).with("AuctionCancelled"),
            BindingBuilder.bind(notifications).to(events).with("OrderStatusChanged"),
            BindingBuilder.bind(notifications).to(events).with("OrderCreated"),
            BindingBuilder.bind(ordersDead).to(dead).with(ORDERS),
            BindingBuilder.bind(notificationsDead).to(dead).with(NOTIFICATIONS), BindingBuilder.bind(market).to(events).with("BidPlaced"), BindingBuilder.bind(market).to(events).with("AuctionExtended"));
    }

    private Queue queue(String name) {
        return QueueBuilder.durable(name).deadLetterExchange(DEAD).deadLetterRoutingKey(name).build();
    }
}
