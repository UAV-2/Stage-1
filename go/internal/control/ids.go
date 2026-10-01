package control

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// ReadIDs lee una lista de IDs (uno por línea) respetando el orden del
// fichero. Las líneas vacías y las que empiezan por "#" se ignoran.
func ReadIDs(path string) ([]int, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var ids []int
	for n, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		id, err := strconv.Atoi(line)
		if err != nil {
			return nil, fmt.Errorf("%s:%d: %q no es un ID de libro", path, n+1, line)
		}
		ids = append(ids, id)
	}
	return ids, nil
}
