package dev.brickfolio.listerizer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@Table(name = "items")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "create_time", nullable = false)
    private long createTime;

    public Item() {
        super();
    }

    public Item(String url, long createTime) {
        this.url = url;
        this.createTime = createTime;
    }

    public long id()         { return id; }
    public String url()      { return url; }
    public long createTime() { return createTime; }
}
