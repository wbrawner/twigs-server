package com.wbrawner.budgetserver.session;

import javax.persistence.Entity;
import javax.persistence.Id;
import java.util.Date;

import static com.wbrawner.budgetserver.Utils.*;

@Entity
public class Session {
    @Id
    private final String id = randomId();
    private final String userId;
    private final String token = randomString(255);
    private Date expiration = twoWeeksFromNow();

    public Session() {
        this("");
    }

    public Session(String userId) {
        this.userId = userId;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }

    public Date getExpiration() {
        return expiration;
    }

    public void setExpiration(Date expiration) {
        this.expiration = expiration;
    }
}