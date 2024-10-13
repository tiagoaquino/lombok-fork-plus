# Lombok Plus

## This project will provide some customs extensions to lombok project (<https://projectlombok.org/>)

**Project Lombok** is a java library that automatically plugs into your editor and build tools, spicing up your java.
Never write another getter or equals method again, with one annotation your class has a fully featured builder, automate your logging variables, and much more.

- `With Lombok`

  ```java
  package com.github.tiagoaquino.dto;

  @Resolver
  public class TestDTO {

      public Long id;

      public String name;

  }
  ```

- `Vanilla Java`

    ```java
    package com.github.tiagoaquino.dto;

    
    public class TestDTO {

        public Long id;

        public String name;

        public void resolveId(java.util.function.Supplier<Long> id) {
            this.id = id.get();
        }

        public void resolveName(java.util.function.Supplier<String> name) {
            this.name = name.get();
        }

    }
    ```

## Use it in `maven`

```xml
<repositories>
    <repository>
        <id>lombok-plus-repository</id>
        <url>https://mymavenrepo.com/repo/i6snxdlhoCnIrcRNt98A/</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok-plus</artifactId>
        <version>1.18.34</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```
