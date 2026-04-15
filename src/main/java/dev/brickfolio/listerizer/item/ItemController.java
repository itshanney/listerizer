package dev.brickfolio.listerizer.item;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Path("/items")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @POST
    public Response create(ItemRequest request) {
        InsertResult result = itemService.create(request);
        Response.Status status = result.isNew() ? Response.Status.CREATED : Response.Status.OK;
        return Response.status(status).entity(toResponse(result.item())).build();
    }

    @GET
    public List<ItemResponse> list() {
        return itemService.list().stream()
                .map(this::toResponse)
                .toList();
    }

    private ItemResponse toResponse(Item item) {
        return new ItemResponse(item.id(), item.url(), item.createTime());
    }
}
