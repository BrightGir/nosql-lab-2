package ru.bright.couch;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import ru.bright.repository.CouchLibraryRepository;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SearchTest {
    @Test void usesCompoundIndexAndNestedMatchWithStableSorting() {
        var client=mock(CouchClient.class); var json=new ObjectMapper();
        when(client.request(anyString(),anyString(),any())).thenAnswer(call -> call.getArgument(2));
        var repository=new CouchLibraryRepository(client,json);
        var result=repository.search("MEETING",Instant.parse("2026-01-01T03:00:00+03:00"),null,"alice","CLOSED","desc",5,"next",false);
        assertThat(result.at("/selector/updatedAt/$gte").asText()).isEqualTo("2026-01-01T00:00:00.000000000Z");
        assertThat(result.at("/selector/history/$elemMatch/by").asText()).isEqualTo("alice");
        assertThat(result.at("/selector/history/$elemMatch/state").asText()).isEqualTo("CLOSED");
        assertThat(result.path("sort").size()).isEqualTo(4);
        assertThat(result.at("/use_index/1").asText()).isEqualTo("by-type-updated");
        assertThat(result.path("bookmark").asText()).isEqualTo("next");
    }
    @Test void rejectsUnboundedPagesAndInvalidSort() {
        var repository=new CouchLibraryRepository(mock(CouchClient.class),new ObjectMapper());
        assertThatThrownBy(()->repository.search("MEETING",null,null,null,null,"asc",201,null,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->repository.search("MEETING",null,null,null,null,"sideways",10,null,false)).isInstanceOf(IllegalArgumentException.class);
    }
}
