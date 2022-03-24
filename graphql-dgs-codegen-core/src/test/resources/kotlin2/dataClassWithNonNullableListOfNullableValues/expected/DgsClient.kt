package kotlin2.dataClassWithNonNullableListOfNullableValues.expected

import kotlin.String
import kotlin2.dataClassWithNonNullableListOfNullableValues.expected.client.QueryProjection

public object DgsClient {
  public fun buildQuery(_projection: QueryProjection.() -> QueryProjection): String {
    val projection = QueryProjection()
    _projection.invoke(projection)
    return "query ${projection.asQuery()}"
  }
}
