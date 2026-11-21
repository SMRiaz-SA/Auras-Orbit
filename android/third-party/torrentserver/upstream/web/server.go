package web

import (
	"crypto/subtle"
	"errors"
	"io"
	"net"
	"net/http"
	"sort"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"server/settings"

	"server/log"
	"server/torr"
	"server/version"
	"server/web/api"
)

var (
	BTS        = torr.NewBTS()
	waitChan   = make(chan error)
	httpServer *http.Server
)

//	@title			Swagger Torrserver API
//	@version		{version.Version}
//	@description	Torrent streaming server.

//	@license.name	GPL 3.0

//	@BasePath	/

//	@securityDefinitions.basic	BasicAuth

// @externalDocs.description	OpenAPI
// @externalDocs.url			https://swagger.io/resources/open-api/
// returns true iff successful
func Start(listener net.Listener, authToken string) error {
	if listener == nil || len(authToken) != 64 {
		return errors.New("invalid Orbit torrent server startup arguments")
	}
	log.TLogln("Start TorrServer " + version.Version + " torrent " + version.GetTorrentVersion())
	ips := getLocalIps()
	if len(ips) > 0 {
		log.TLogln("Local IPs:", ips)
	}
	err := BTS.Connect()
	if err != nil {
		log.TLogln("BTS.Connect() error!", err) // waitChan <- err
		//os.Exit(1)                              // return
		_ = listener.Close()
		BTS.Disconnect()
		settings.CloseDB()
		return errors.New("torrent engine initialization failed")
	}

	gin.SetMode(gin.ReleaseMode)

	route := gin.New()
	route.Use(requireOrbitAuth(authToken), gin.RecoveryWithWriter(io.Discard))

	route.GET("/echo", echo)

	api.SetupOrbitRoutes(&route.RouterGroup)

	httpServer = &http.Server{
		Handler: route,
	}

	serveErr := make(chan error, 1)
	go func() {
		log.TLogln("Start http server at port", settings.Port)
		err := httpServer.Serve(listener)
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			select {
			case serveErr <- err:
			default:
			}
		}
	}()

	if err := awaitHTTPServer(listener, authToken, serveErr); err != nil {
		_ = httpServer.Close()
		_ = listener.Close()
		BTS.Disconnect()
		settings.CloseDB()
		return err
	}
	return nil
}

func awaitHTTPServer(listener net.Listener, authToken string, serveErr <-chan error) error {
	addr, ok := listener.Addr().(*net.TCPAddr)
	if !ok || addr.Port < 1 || addr.Port > 65535 || !addr.IP.Equal(net.IPv4(127, 0, 0, 1)) {
		return errors.New("Orbit torrent listener is not IPv4 loopback")
	}

	url := "http://127.0.0.1:" + strconv.Itoa(addr.Port) + "/echo"
	client := &http.Client{
		Timeout: 500 * time.Millisecond,
		Transport: &http.Transport{
			Proxy: nil,
		},
	}
	defer client.CloseIdleConnections()

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		request, err := http.NewRequest(http.MethodGet, url, nil)
		if err != nil {
			return errors.New("Orbit torrent readiness request failed")
		}
		request.Header.Set("X-Orbit-Torrent-Token", authToken)
		response, err := client.Do(request)
		if err == nil {
			response.Body.Close()
			if response.StatusCode != http.StatusOK {
				return errors.New("Orbit torrent HTTP server returned an unexpected status")
			}
			return nil
		}

		select {
		case serveErr := <-serveErr:
			if serveErr != nil {
				return errors.New("Orbit torrent HTTP server failed to start")
			}
		default:
		}
		time.Sleep(25 * time.Millisecond)
	}
	return errors.New("Orbit torrent HTTP server readiness timed out")
}

func requireOrbitAuth(expected string) gin.HandlerFunc {
	return func(c *gin.Context) {
		provided := c.GetHeader("X-Orbit-Torrent-Token")
		if len(provided) != len(expected) || subtle.ConstantTimeCompare([]byte(provided), []byte(expected)) != 1 {
			c.AbortWithStatus(http.StatusUnauthorized)
			return
		}
		c.Next()
	}
}

func Wait() error {
	return <-waitChan
}

func Stop() {
	if httpServer != nil {
		httpServer.Close()
	}
	BTS.Disconnect()
	//waitChan <- nil
}

// echo godoc
//
//	@Summary		Tests server status
//	@Description	Tests whether server is alive or not
//
//	@Tags			API
//
//	@Produce		plain
//	@Success		200	{string}	string	"Server version"
//	@Router			/echo [get]
func echo(c *gin.Context) {
	c.String(200, "%v", version.Version)
}

func getLocalIps() []string {
	ifaces, err := net.Interfaces()
	if err != nil {
		log.TLogln("Error get local IPs")
		return nil
	}
	var list []string
	for _, i := range ifaces {
		addrs, _ := i.Addrs()
		if i.Flags&net.FlagUp == net.FlagUp {
			for _, addr := range addrs {
				var ip net.IP
				switch v := addr.(type) {
				case *net.IPNet:
					ip = v.IP
				case *net.IPAddr:
					ip = v.IP
				}
				if !ip.IsLoopback() && !ip.IsLinkLocalUnicast() && !ip.IsLinkLocalMulticast() {
					list = append(list, ip.String())
				}
			}
		}
	}
	sort.Strings(list)
	return list
}
