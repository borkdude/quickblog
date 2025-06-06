# Quickblog Project Summary

## Overview

Quickblog is a **static site generator for blogs** written in Clojure, designed for rapid blog creation and deployment. It transforms Markdown posts with metadata into a complete static website with HTML pages, RSS feeds, tag pages, and social media integration. The project is compatible with both **Babashka** (fast startup scripting) and **standard Clojure**.

**Key Features:**
- Markdown-to-HTML blog generation with metadata support
- Hot-reload development server with file watching
- Social media sharing cards and favicon support
- Tag-based organization and RSS feed generation
- Template system with customizable themes
- Intelligent caching system for fast rebuilds
- Previous/next post linking

## Architecture & Core Components

### Main Namespaces

#### `quickblog.api` - Core Blog Engine
- **Purpose**: Primary API and blog generation logic
- **Key Functions**:
  - `render [opts]` - Main rendering function that generates the entire site
  - `quickblog [opts]` - Main entry point that orchestrates site generation
  - `new [opts]` - Creates new blog posts from templates
  - `serve [opts]` - Starts development HTTP server
  - `watch [opts]` - File watcher for hot-reload development
  - `clean [opts]` - Removes cache and output directories
  - `migrate [opts]` - Migrates from legacy posts.edn format

#### `quickblog.cli` - Command Line Interface
- **Purpose**: CLI wrapper around the API using babashka.cli
- **Key Functions**:
  - `dispatch [opts & args]` - Routes CLI commands to API functions
  - `run [default-opts]` - Main CLI entry point
- **Commands**: `new`, `render`, `watch`, `serve`, `clean`, `migrate`, `refresh-templates`

#### `quickblog.internal` - Implementation Details
- **Purpose**: Internal utilities and core rendering logic
- **Key Areas**:
  - Post loading and caching (`load-posts`, `load-cache`, `write-cache!`)
  - Markdown processing (`markdown->html`, `pre-process-markdown`)
  - Template rendering (`render-page`, `write-post!`, `write-page!`)
  - File system operations (`copy-tree-modified`, `modified-since?`)
  - Metadata transformation and validation

### Configuration System

**CLI Specification Groups** (in `quickblog.api`):
- `:blog-metadata` - Title, author, description, root URL
- `:optional-metadata` - About link, Twitter handle, discussion links
- `:post-config` - Default metadata, number of index posts
- `:input-directories` - Assets, posts, templates directories
- `:output-directories` - Output base, assets output, tags output
- `:caching` - Cache directory, force render options
- `:social-sharing` - Blog images for social media cards
- `:favicon` - Favicon configuration

## Key Dependencies & Their Roles

```clojure
{:deps {
  ;; Core template engine for HTML generation
  selmer/selmer {:mvn/version "1.12.53"}
  
  ;; Markdown processing (MultiMarkdown flavor)
  markdown-clj/markdown-clj {:mvn/version "1.12.2"}
  
  ;; CLI argument parsing and command dispatch
  org.babashka/cli {:mvn/version "0.6.50"}
  
  ;; Development HTTP server with hot-reload
  org.babashka/http-server {:mvn/version "0.1.11"}
  
  ;; File system utilities for cross-platform file operations
  babashka/fs {:mvn/version "0.1.6"}
  
  ;; XML generation for RSS feeds and sitemaps
  org.clojure/data.xml {:mvn/version "0.2.0-alpha6"}
  
  ;; Code rewriting for metadata manipulation
  rewrite-clj/rewrite-clj {:mvn/version "1.1.45"}
}}
```

## Project Structure

```
quickblog/
├── src/quickblog/           # Main source code
│   ├── api.clj             # Core blog engine & public API
│   ├── cli.clj             # Command-line interface
│   └── internal.clj        # Implementation utilities
├── test/quickblog/          # Test suite
│   ├── api_test.clj        # API integration tests
│   └── test_runner.clj     # Test execution
├── resources/               # Built-in templates and assets
│   └── quickblog/
│       ├── templates/      # Default HTML templates
│       └── assets/         # Default favicon assets
├── deps.edn                # Clojure dependency specification
├── bb.edn                  # Babashka tasks and configuration
└── README.md              # Comprehensive documentation
```

## Development Workflow

### Setting Up a New Blog

1. **Create bb.edn** with quickblog dependency and tasks:
```clojure
{:deps {io.github.borkdude/quickblog {:git/sha "latest-sha"}}
 :tasks {:requires ([quickblog.cli :as cli])
         :init (def opts {:blog-title "My Blog" 
                         :blog-description "A blog about X"})
         quickblog {:task (cli/dispatch opts)}}}
```

2. **Create first post**: `bb quickblog new --file "hello.md" --title "Hello World"`

3. **Start development**: `bb quickblog watch` (starts server + file watcher)

### Development Commands

```bash
# Create new post
bb quickblog new --file "post.md" --title "My Post"

# Render static site
bb quickblog render

# Development server with hot-reload
bb quickblog watch

# Clean cache and output
bb quickblog clean

# Update templates to latest
bb quickblog refresh-templates
```

## Template System

**Template Files** (in `:templates-dir`, default: `templates/`):
- `base.html` - Base page layout with `{{body}}` placeholder
- `post.html` - Individual post page template
- `index.html` - Homepage with recent posts
- `tags.html` - Tag overview page
- `post-links.html` - Post list component for archives/tags
- `style.css` - Site-wide CSS styles
- `favicon.html` - Favicon includes (when enabled)

**Template Variables** (Selmer syntax):
- `{{blog-title}}`, `{{blog-description}}` - Site metadata
- `{{posts}}`, `{{tags}}` - Collections of posts/tags
- `{{prev}}`, `{{next}}` - Previous/next post navigation
- `{{twitter-handle}}`, `{{discuss-link}}` - Social features

## Post Format & Metadata

**Post Structure** (Markdown with MultiMarkdown metadata):
```markdown
Title: My Amazing Post
Date: 2023-12-01
Tags: clojure, blogging
Image: assets/my-post-preview.png
Image-Alt: Screenshot of the amazing feature
Description: This post explains how to do amazing things

Your blog content goes here in **Markdown**.

<!-- end-of-preview -->

More content that won't appear on the homepage.
```

**Required Metadata**: `Title`, `Date`, `Tags`
**Optional Metadata**: `Image`, `Image-Alt`, `Description`, `Twitter-Handle`, `Discuss`, `Preview`

## Caching System

**Intelligent Caching** (`quickblog.internal`):
- **Post Cache**: `.work/cache.edn` stores parsed post metadata
- **File Modification Tracking**: Only regenerates changed content
- **Dependency Tracking**: Monitors templates and system files
- **Cache Invalidation**: Automatic when source files change

**Cache Functions**:
- `load-cache` - Loads existing cache from disk
- `write-cache!` - Persists current cache state
- `modified-posts` - Identifies posts needing regeneration
- `modified-tags` - Identifies tag pages needing updates

## Extension Points

### Custom Templates
1. Run `bb quickblog refresh-templates` to get latest defaults
2. Modify templates in your `:templates-dir`
3. Use any Selmer template features (loops, conditionals, filters)

### Custom Post Templates
- Create `new-post.md` template for `bb quickblog new --template-file`
- Support for custom metadata fields and default values

### Custom Metadata Processing
- Extend `metadata-transformers` in `quickblog.internal`
- Add validation in `validate-metadata`
- Use `transform-metadata` for custom field processing

### Plugin Architecture
- Library design allows wrapping core functions
- Custom rendering pipelines via `render` function composition
- Integration with external tools via CLI or API

## Implementation Patterns

### Functional Pipeline Architecture
- **Immutable Options Map**: Configuration flows through all functions
- **Data Transformation**: Posts as maps through processing pipeline  
- **Functional Composition**: Small, composable functions for each step

### Error Handling
- **Validation**: Metadata validation with clear error messages
- **Graceful Degradation**: Continue processing valid posts when some fail
- **File System Safety**: Atomic operations and backup mechanisms

### Performance Optimizations
- **Lazy Loading**: Posts loaded only when needed
- **Incremental Updates**: Only regenerate changed content
- **Efficient File Operations**: Batch operations and minimal I/O

## Testing Strategy

**Test Structure** (`test/quickblog/api_test.clj`):
- **Integration Tests**: Full rendering pipeline tests
- **Temporary Directories**: Isolated test environments
- **Fixture Management**: Setup/teardown of test data
- **Feature Testing**: Social sharing, caching, templates

**Key Test Areas**:
- Post creation and metadata processing
- Template rendering and customization
- Caching behavior and invalidation
- Social media integration
- CLI command functionality

This summary provides LLM assistants with the context needed to understand quickblog's architecture, help with configuration, debug issues, and suggest extensions or modifications to the codebase.
