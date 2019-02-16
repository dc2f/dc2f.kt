# DC2F - Static site generator for developers based on Kotlin

Still under heavy development. ;-)

## Main focus

* Semantic Content, Type Safety
* Consistency (Internal links, images, resources, ...)
* Fail fast (e.g. fail during parsing for incomplete data, not during rendering.. or not at all)
* For now.. better explicit simplicity, than implicit magic.

## What's the name?

Not sure yet, you tell me. Just call it dc2f for now.

## Implementation right now

* Jackson for deserializing YML files (with MrBean)
* Kotlin interfaces/abstract classes for defining content structure
* kotlinx.html for rendering templates
* RichText content using one of:
    * Markdown (using flexmark-java)
    * Pebble
    * (Mustache.. TO BE REMOVED)

## Things Todo Someday

* Multilingual support
* (Better, or any at all) IDE Support (autocompletion in YAML files, etc.)

## Content Types

All content types are defined using Kotlin interfaces or abstract classes inheriting from
`ContentDef`.

## Content Layout

Right now content is placed in a combination of Directories with `_index.yml` files and external properties.

### Right Now

Each directory is one content "node", element, item, however you want to call it. 
The structure of which is defined by a subinterface/class of `ContentDef`

Example:

```
root/
  _index.yml # Defines the root properties of the `Website`
  001.articles.blog/
    _index.yml
    001.my-first-blog-post.article/
      _index.yml
      body.md
    002.another-blog-post.article/
      _index.yml
      body.md
    [...]
```

So right now, each file or directory has one of the following structure:

* *sortorder*`.`*slug*`.`*type* (e.g. 001.my-first-blog-post.article)
* *propertyname*`.`*type* (e.g. body.md)

The definition of the above structure could look like:

```kotlin
// content definition for the blog "folder"
@Nestable("blog")
interface Blog: ContentBranchDef<Article>

// content definition for the articles inside the Blog "folder"
@Nestable("article")
interface Article: ContentDef {
    var author: String
    val date: ZonedDateTime
    val categories: Array<String>
    val seo: PageSeo
    val title: String
    var teaser: ImageAsset
    @set:JacksonInject("body")
    var body: Markdown
}
```

`ContentBranchDef` is actually defined from dc2f itself and is a simple `ContentDef` 
with a `children` attribute:

```kotlin
interface ContentBranchDef<CHILD_TYPE: ContentDef> : ContentDef {
    @set:JacksonInject("children")
    var children: List<CHILD_TYPE>
}
```

### Thoughts on naming consistencies

There is some weird magic going on to detect what is a child, and
what is a property. This might prevent us from differentiating
between "children" nodes and other list properties.

#### Requirements

* It should be "short" for the default case (normal "children" must not have any special annotation)
* Make as much as possible optional (e.g. optional slug(?) or optional sortorder)
* Keep it consistent
* Content must be addressable uniquely using "content paths"

#### Ideas

So to getting rid of this magic there are some Ideas:

* (1) Prefix attributes with @ - e.g. @body.md
  * This allows having also sub nodes which are not the special `children` property:
    @partials.001.somedata.partial/
    @partials.002.anotherdata.partial/
* (2) Require attributes which are lists to be a subfolder
  * partials/001.somedata.partial/
    partials/002.some-other-data.partial/
  * ie. *attributename*`/`*sortorder*`.`*slug*`.`type`
* (3)Require that sub nodes have a custom prefix
  * e.g. instead of `001.my-first-blogpost.article` something like `children.001.my-first-blogpost.article`
    I guess this could be simplified, e.g. by aliasing `children` to `_` like `_.001.my-first-blogpost.article`

Following examples assume a landing page with some Subpages (which are children) and
partials which are rendered on the same page, but should be separate content items.

* Landing Page `/landing-page`
  * child 1: Sub Page A `/landing-page/sub-page-a`
  * child 2: Sub Page B `/landing-page/sub-page-b`
  * partial 1: Hero Element `/landing-page/@partials/great-product`
  * partial 2: Intro Element `/landing-page/@partials/introduction`
  * partial 3: Feature A `landing-page/@partials/feature-a`
  * partial 4: Feature B `landing-page/@partials/feature-b`

Imaginary data structure for the above folder structure:

```kotlin
interface ExampleWebsite : Website {
    @JacksonInject("main")
    val main: LandingPage
}

interface LandingPage : ContentDef {
    @JacksonInject("") // special case, empty attribute injection denotes children of that node.
    val children: List<SubPage>
    @JacksonInject("partials")
    val partials: List<Partial>
    
    val abstract: Markdown
}

interface SubPage : ContentDef {
    // ...
}

sealed class Partial : ContentDef {
    abstract class Hero : ContentDef {
        // ...
    }
    abstract class Intro : ContentDef {
        // ...
    }
    abstract class Feature : ContentDef {
        // ...
    }
}

```

##### Examples for (2)


```
root/
  _index.yml
  @main.landingpage/
    _index.yml
    @abstract.md
    001.sub-page-a.page/
        _index.yml
        @body.md
    002.sub-page-b.page/
    # partial: List<Partials>
    @partials/
        001.great-product.hero
        002.introduction.intro
        003.feature-a.feature
        004.feature-b.feature

# Alternatively, with some optional things removed
root/
  _index.yml
  @main.landingpage/
    _index.yml
    @abstract.md
    # sub pages without sort order (could be sorted during rendering by attribute of the page, e.g. date otherwise based on their alphabetical name.)
    sub-page-a.page/
    sub-page-b.page/
    @partials/
        # partials without a slug.. ie. they are referenced only by their sort order
        # technically this means, that the sort order is simply used as slug.
        001.hero
        002.intro
        003.feature (ie. ContentPath is /@main/@partials/003)
        004.feature
```
