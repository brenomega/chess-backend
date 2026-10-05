package com.projetoxadrez.backend.game.chess;

import java.util.List;
import java.util.Objects;

public record ChessPosition(
        Board board,
        Side sideToMove,
        CastlingRights castlingRights,
        Square enPassantTarget,
        int halfmoveClock,
        int fullmoveNumber) {

    private static final LegalMoveService LEGAL_MOVES = new LegalMoveService();

    public ChessPosition(Board board, Side sideToMove) {
        this(board, sideToMove, CastlingRights.none(), null, 0, 1);
    }

    public ChessPosition(
            Board board,
            Side sideToMove,
            CastlingRights castlingRights,
            Square enPassantTarget) {
        this(board, sideToMove, castlingRights, enPassantTarget, 0, 1);
    }

    public ChessPosition {
        Objects.requireNonNull(board, "board must not be null");
        Objects.requireNonNull(sideToMove, "sideToMove must not be null");
        Objects.requireNonNull(castlingRights, "castlingRights must not be null");
        if (halfmoveClock < 0) {
            throw new IllegalArgumentException("halfmoveClock must not be negative");
        }
        if (fullmoveNumber < 1) {
            throw new IllegalArgumentException("fullmoveNumber must be positive");
        }
    }

    public static ChessPosition initial() {
        return new ChessPosition(Board.initial(), Side.WHITE, CastlingRights.initial(), null, 0, 1);
    }

    public static ChessPosition fromFen(String fen) {
        if (fen == null) {
            throw new IllegalArgumentException("FEN must not be null");
        }
        String[] fields = fen.split(" ", -1);
        if (fields.length != 6) {
            throw new IllegalArgumentException("FEN must contain six fields");
        }

        Board board = boardFromFen(fields[0]);
        Side sideToMove = sideFromFen(fields[1]);
        CastlingRights castlingRights = castlingRightsFromFen(fields[2]);
        Square enPassantTarget = enPassantTargetFromFen(fields[3], sideToMove);
        int halfmoveClock = nonNegativeNumber(fields[4], "halfmove clock");
        int fullmoveNumber = positiveNumber(fields[5], "fullmove number");
        return new ChessPosition(
                board,
                sideToMove,
                castlingRights,
                enPassantTarget,
                halfmoveClock,
                fullmoveNumber);
    }

    public List<Move> legalMoves() {
        return LEGAL_MOVES.legalMoves(this);
    }

    public boolean isInCheck(Side side) {
        return LEGAL_MOVES.isInCheck(this, Objects.requireNonNull(side, "side must not be null"));
    }

    public ChessPosition apply(Move move) {
        Objects.requireNonNull(move, "move must not be null");
        if (!LEGAL_MOVES.isLegal(this, move)) {
            throw new IllegalMoveException("Illegal move: " + move.toUci());
        }
        return applyUnchecked(move);
    }

    ChessPosition applyUnchecked(Move move) {
        Piece movingPiece = board.pieceAt(move.from()).orElseThrow();
        Piece capturedPiece = board.pieceAt(move.to()).orElse(null);
        Board updated = board.move(move.from(), move.to());

        if (isEnPassant(move, movingPiece, capturedPiece)) {
            updated = updated.withoutPiece(new Square(move.to().file(), move.from().rank()));
        }
        if (isCastling(move, movingPiece)) {
            int rank = move.from().rank();
            boolean kingSide = move.to().file() == 6;
            updated = updated.move(new Square(kingSide ? 7 : 0, rank), new Square(kingSide ? 5 : 3, rank));
        }
        if (move.promotion() != null) {
            updated = updated.replace(move.to(), new Piece(movingPiece.side(), move.promotion()));
        }

        CastlingRights updatedRights = updateCastlingRights(move, movingPiece, capturedPiece);
        Square nextEnPassantTarget = enPassantTarget(move, movingPiece);
        int nextHalfmoveClock = movingPiece.type() == PieceType.PAWN || capturedPiece != null
                ? 0
                : halfmoveClock + 1;
        int nextFullmoveNumber = sideToMove == Side.BLACK ? fullmoveNumber + 1 : fullmoveNumber;
        return new ChessPosition(
                updated,
                sideToMove.opposite(),
                updatedRights,
                nextEnPassantTarget,
                nextHalfmoveClock,
                nextFullmoveNumber);
    }

    public String toFen() {
        return "%s %s %s %s %d %d".formatted(
                boardToFen(),
                sideToMove == Side.WHITE ? "w" : "b",
                castlingRightsToFen(castlingRights),
                enPassantTarget == null ? "-" : enPassantTarget.toAlgebraic(),
                halfmoveClock,
                fullmoveNumber);
    }

    private String boardToFen() {
        StringBuilder placement = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            if (rank < 7) {
                placement.append('/');
            }
            int emptySquares = 0;
            for (int file = 0; file < 8; file++) {
                Piece piece = board.pieceAt(new Square(file, rank)).orElse(null);
                if (piece == null) {
                    emptySquares++;
                    continue;
                }
                if (emptySquares > 0) {
                    placement.append(emptySquares);
                    emptySquares = 0;
                }
                placement.append(pieceToFen(piece));
            }
            if (emptySquares > 0) {
                placement.append(emptySquares);
            }
        }
        return placement.toString();
    }

    private CastlingRights updateCastlingRights(Move move, Piece movingPiece, Piece capturedPiece) {
        CastlingRights updated = castlingRights;
        if (movingPiece.type() == PieceType.KING) {
            updated = updated.withoutSide(movingPiece.side());
        }
        if (movingPiece.type() == PieceType.ROOK) {
            updated = updated.withoutRookAt(move.from());
        }
        if (capturedPiece != null && capturedPiece.type() == PieceType.ROOK) {
            updated = updated.withoutRookAt(move.to());
        }
        return updated;
    }

    private static Square enPassantTarget(Move move, Piece piece) {
        if (piece.type() != PieceType.PAWN || Math.abs(move.to().rank() - move.from().rank()) != 2) {
            return null;
        }
        return new Square(move.from().file(), (move.from().rank() + move.to().rank()) / 2);
    }

    private boolean isEnPassant(Move move, Piece piece, Piece capturedPiece) {
        return piece.type() == PieceType.PAWN
                && capturedPiece == null
                && move.to().equals(enPassantTarget)
                && move.from().file() != move.to().file();
    }

    private static boolean isCastling(Move move, Piece piece) {
        return piece.type() == PieceType.KING && Math.abs(move.to().file() - move.from().file()) == 2;
    }

    private static Board boardFromFen(String value) {
        String[] ranks = value.split("/", -1);
        if (ranks.length != 8) {
            throw new IllegalArgumentException("FEN board must contain eight ranks");
        }

        Board board = Board.empty();
        for (int fenRank = 0; fenRank < ranks.length; fenRank++) {
            int file = 0;
            boolean previousWasDigit = false;
            for (char symbol : ranks[fenRank].toCharArray()) {
                if (symbol >= '1' && symbol <= '8') {
                    if (previousWasDigit) {
                        throw new IllegalArgumentException("FEN rank contains adjacent empty-square counts");
                    }
                    file += symbol - '0';
                    previousWasDigit = true;
                } else {
                    if (file >= 8) {
                        throw new IllegalArgumentException("FEN rank exceeds eight squares");
                    }
                    board = board.withPiece(new Square(file, 7 - fenRank), pieceFromFen(symbol));
                    file++;
                    previousWasDigit = false;
                }
            }
            if (file != 8) {
                throw new IllegalArgumentException("FEN rank must contain eight squares");
            }
        }
        return board;
    }

    private static Piece pieceFromFen(char symbol) {
        Side side = Character.isUpperCase(symbol) ? Side.WHITE : Side.BLACK;
        PieceType type = switch (Character.toLowerCase(symbol)) {
            case 'k' -> PieceType.KING;
            case 'q' -> PieceType.QUEEN;
            case 'r' -> PieceType.ROOK;
            case 'b' -> PieceType.BISHOP;
            case 'n' -> PieceType.KNIGHT;
            case 'p' -> PieceType.PAWN;
            default -> throw new IllegalArgumentException("FEN contains an invalid piece");
        };
        return new Piece(side, type);
    }

    private static char pieceToFen(Piece piece) {
        char symbol = switch (piece.type()) {
            case KING -> 'k';
            case QUEEN -> 'q';
            case ROOK -> 'r';
            case BISHOP -> 'b';
            case KNIGHT -> 'n';
            case PAWN -> 'p';
        };
        return piece.side() == Side.WHITE ? Character.toUpperCase(symbol) : symbol;
    }

    private static Side sideFromFen(String value) {
        return switch (value) {
            case "w" -> Side.WHITE;
            case "b" -> Side.BLACK;
            default -> throw new IllegalArgumentException("FEN side to move must be w or b");
        };
    }

    private static CastlingRights castlingRightsFromFen(String value) {
        if ("-".equals(value)) {
            return CastlingRights.none();
        }
        CastlingRights rights = new CastlingRights(
                value.indexOf('K') >= 0,
                value.indexOf('Q') >= 0,
                value.indexOf('k') >= 0,
                value.indexOf('q') >= 0);
        if (!castlingRightsToFen(rights).equals(value)) {
            throw new IllegalArgumentException("FEN castling rights are invalid");
        }
        return rights;
    }

    private static String castlingRightsToFen(CastlingRights rights) {
        StringBuilder value = new StringBuilder();
        if (rights.whiteKingSide()) {
            value.append('K');
        }
        if (rights.whiteQueenSide()) {
            value.append('Q');
        }
        if (rights.blackKingSide()) {
            value.append('k');
        }
        if (rights.blackQueenSide()) {
            value.append('q');
        }
        return value.isEmpty() ? "-" : value.toString();
    }

    private static Square enPassantTargetFromFen(String value, Side sideToMove) {
        if ("-".equals(value)) {
            return null;
        }
        Square target = Square.fromAlgebraic(value);
        int expectedRank = sideToMove == Side.WHITE ? 5 : 2;
        if (target.rank() != expectedRank) {
            throw new IllegalArgumentException("FEN en passant target is invalid for the side to move");
        }
        return target;
    }

    private static int nonNegativeNumber(String value, String field) {
        int parsed = fenNumber(value, field);
        if (parsed < 0) {
            throw new IllegalArgumentException("FEN %s must not be negative".formatted(field));
        }
        return parsed;
    }

    private static int positiveNumber(String value, String field) {
        int parsed = fenNumber(value, field);
        if (parsed < 1) {
            throw new IllegalArgumentException("FEN %s must be positive".formatted(field));
        }
        return parsed;
    }

    private static int fenNumber(String value, String field) {
        if (!value.matches("[0-9]+")) {
            throw new IllegalArgumentException("FEN %s is invalid".formatted(field));
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("FEN %s is invalid".formatted(field), exception);
        }
    }
}
