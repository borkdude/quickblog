FROM clojure:temurin-21-tools-deps

CMD ["clojure", "-M:test:nrepl"]
