package kotlin2.interfaceWithInterfaceInheritance.expected.client

import com.netflix.graphql.dgs.client.codegen.GraphQLProjection

public class SeedProjection : GraphQLProjection() {
  public val name: SeedProjection
    get() {
      field("name")
      return this
    }
}
