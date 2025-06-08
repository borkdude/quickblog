FROM clojure:temurin-21-tools-deps

CMD ["clojure", "-M:clj-1.12:test:nrepl"]
