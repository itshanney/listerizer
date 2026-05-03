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

    @Column(name = "title", length = 1024)
    private String title;

    @Column(name = "has_been_read", nullable = false)
    private boolean hasBeenRead;

    public Item() {
        super();
    }

    public Item(String url, long createTime, String title, boolean hasBeenRead) {
        this.url = url;
        this.createTime = createTime;
        this.title = title;
        this.hasBeenRead = hasBeenRead;
    }

    public long id()             { return id; }
    public String url()          { return url; }
    public long createTime()     { return createTime; }
    public String title()        { return title; }
    public boolean hasBeenRead() { return hasBeenRead; }

    public void setTitle(String title)              { this.title = title; }
    public void setHasBeenRead(boolean hasBeenRead) { this.hasBeenRead = hasBeenRead; }
}
