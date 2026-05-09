#define TOKEN_SIZE 20
constexpr size_t MAX_PAYLOAD = 1024 * 1024;

 std::string adb_auth_sign(RSA* key, const char* token, size_t token_size);