package torrServer

import (
	server "server"
)

func StartTorrentServer(pathdb string, port int, authToken string) int {
	return server.Start(pathdb, port, false, false, authToken)
}
