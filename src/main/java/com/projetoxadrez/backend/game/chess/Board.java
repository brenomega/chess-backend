package com.projetoxadrez.backend.game.chess;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Board {

    private final Map<Square, Piece> squares;

    private Board(Map<Square, Piece> squares) {
        this.squares = Map.copyOf(squares);
    }

    public static Board empty() {
        return new Board(Map.of());
    }

    public static Board initial() {
        Map<Square, Piece> pieces = new HashMap<>();
        placeBackRank(pieces, Side.WHITE, 0);
        placePawns(pieces, Side.WHITE, 1);
        placePawns(pieces, Side.BLACK, 6);
        placeBackRank(pieces, Side.BLACK, 7);
        return new Board(pieces);
    }

    public Optional<Piece> pieceAt(Square square) {
        return Optional.ofNullable(squares.get(Objects.requireNonNull(square, "square must not be null")));
    }

    public Board withPiece(Square square, Piece piece) {
        Objects.requireNonNull(square, "square must not be null");
        Objects.requireNonNull(piece, "piece must not be null");
        if (squares.containsKey(square)) {
            throw new IllegalArgumentException("Square is already occupied: " + square);
        }

        Map<Square, Piece> updated = new HashMap<>(squares);
        updated.put(square, piece);
        return new Board(updated);
    }

    public Board withoutPiece(Square square) {
        Objects.requireNonNull(square, "square must not be null");
        if (!squares.containsKey(square)) {
            return this;
        }

        Map<Square, Piece> updated = new HashMap<>(squares);
        updated.remove(square);
        return new Board(updated);
    }

    public int pieceCount() {
        return squares.size();
    }

    Map<Square, Piece> pieces() {
        return squares;
    }

    Board apply(Move move) {
        Map<Square, Piece> updated = new HashMap<>(squares);
        Piece piece = updated.remove(move.from());
        updated.put(move.to(), piece);
        return new Board(updated);
    }

    private static void placePawns(Map<Square, Piece> pieces, Side side, int rank) {
        for (int file = 0; file < 8; file++) {
            pieces.put(new Square(file, rank), new Piece(side, PieceType.PAWN));
        }
    }

    private static void placeBackRank(Map<Square, Piece> pieces, Side side, int rank) {
        PieceType[] order = {
                PieceType.ROOK,
                PieceType.KNIGHT,
                PieceType.BISHOP,
                PieceType.QUEEN,
                PieceType.KING,
                PieceType.BISHOP,
                PieceType.KNIGHT,
                PieceType.ROOK
        };
        for (int file = 0; file < order.length; file++) {
            pieces.put(new Square(file, rank), new Piece(side, order[file]));
        }
    }

    @Override
    public boolean equals(Object candidate) {
        return this == candidate || candidate instanceof Board board && squares.equals(board.squares);
    }

    @Override
    public int hashCode() {
        return squares.hashCode();
    }
}
