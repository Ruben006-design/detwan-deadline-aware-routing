package network;
public class Router {

    private int id;
    private String name;

    public Router(int id) {
        this.id = id;
        this.name = "Router " + id;
    }

    public Router(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}