package utils

import (
	"encoding/base32"
	"math/rand"

	"golang.org/x/time/rate"
)

var defTrackers = []string{}

func SetDefTrackers(trackers []string) {
	defTrackers = trackers
}

func GetTrackerFromFile() []string {
	return nil
}

func GetDefTrackers() []string {
	return defTrackers
}

func PeerIDRandom(peer string) string {
	randomBytes := make([]byte, 32)
	_, err := rand.Read(randomBytes)
	if err != nil {
		panic(err)
	}
	return peer + base32.StdEncoding.EncodeToString(randomBytes)[:20-len(peer)]
}

func Limit(i int) *rate.Limiter {
	l := rate.NewLimiter(rate.Inf, 0)
	if i > 0 {
		b := i
		if b < 16*1024 {
			b = 16 * 1024
		}
		l = rate.NewLimiter(rate.Limit(i), b)
	}
	return l
}
