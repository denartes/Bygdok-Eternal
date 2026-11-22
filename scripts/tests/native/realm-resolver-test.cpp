#include "Asio/IoContext.h"
#include "Asio/Resolver.h"
#include <cstdio>

int main()
{
    Acore::Asio::IoContext context;
    Acore::Asio::Resolver resolver(context);
    int failures = 0;
    auto verify = [&](char const* host, char const* service, char const* address, unsigned short port)
    {
        auto result = resolver.Resolve(boost::asio::ip::tcp::v4(), host, service);
        bool passed = result && result->address().to_string() == address && result->port() == port;
        std::printf("%s host=%s service='%s'\n", passed ? "PASS" : "FAIL", host, service);
        failures += !passed;
    };

    verify("192.168.0.200", "", "192.168.0.200", 0);
    verify("127.0.0.1", "", "127.0.0.1", 0);
    verify("255.255.255.0", "", "255.255.255.0", 0);
    verify("localhost", "", "127.0.0.1", 0);
    verify("127.0.0.1", "8085", "127.0.0.1", 8085);

    bool invalidServiceRejected = !resolver.Resolve(boost::asio::ip::tcp::v4(), "127.0.0.1", "invalid service");
    bool invalidAddressRejected = !resolver.Resolve(boost::asio::ip::tcp::v4(), "999.999.999.999", "");
    std::printf("%s invalid service\n", invalidServiceRejected ? "PASS" : "FAIL");
    std::printf("%s invalid address\n", invalidAddressRejected ? "PASS" : "FAIL");
    failures += !invalidServiceRejected + !invalidAddressRejected;
    return failures ? 1 : 0;
}
